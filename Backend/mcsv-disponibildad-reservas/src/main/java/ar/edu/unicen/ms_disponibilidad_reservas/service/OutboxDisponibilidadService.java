package ar.edu.unicen.ms_disponibilidad_reservas.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.model.EstadoNotificacionOutbox;
import ar.edu.unicen.ms_disponibilidad_reservas.model.NotificacionDisponibilidadOutbox;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.NotificacionDisponibilidadOutboxRepository;

/** Claims durable notifications, sends outside inventory transactions, and schedules retries. */
@Service
public class OutboxDisponibilidadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDisponibilidadService.class);
    private static final int MAX_LONGITUD_ERROR = 2000;

    private final NotificacionDisponibilidadOutboxRepository repository;
    private final Ms1DisponibilidadNotifier notifier;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final long leaseMillis;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final int maxBatch;

    public OutboxDisponibilidadService(
            NotificacionDisponibilidadOutboxRepository repository,
            Ms1DisponibilidadNotifier notifier,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Value("${ms2.outbox.lease-ms:15000}") long leaseMillis,
            @org.springframework.beans.factory.annotation.Value("${ms2.outbox.initial-backoff-ms:1000}") long initialBackoffMillis,
            @org.springframework.beans.factory.annotation.Value("${ms2.outbox.max-backoff-ms:300000}") long maxBackoffMillis,
            @org.springframework.beans.factory.annotation.Value("${ms2.outbox.max-batch:50}") int maxBatch,
            @org.springframework.beans.factory.annotation.Value("${ms2.outbox.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @org.springframework.beans.factory.annotation.Value("${ms2.outbox.read-timeout-ms:5000}") long readTimeoutMillis) {
        long maxHttpMillis = Math.addExact(connectTimeoutMillis, readTimeoutMillis);
        if (leaseMillis <= maxHttpMillis || initialBackoffMillis <= 0
                || maxBackoffMillis < initialBackoffMillis || maxBatch <= 0) {
            throw new IllegalArgumentException("La configuración de Outbox debe contener valores positivos y coherentes.");
        }
        this.repository = repository;
        this.notifier = notifier;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.leaseMillis = leaseMillis;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.maxBatch = maxBatch;
    }

    /** Processes at most one bounded batch; safe to invoke from multiple MS2 instances. */
    public int procesarPendientes() {
        int procesadas = 0;
        while (procesadas < maxBatch) {
            Claim claim = transaction.execute(status -> reclamar());
            if (claim == null) break;
            enviar(claim);
            procesadas++;
        }
        return procesadas;
    }

    private Claim reclamar() {
        LocalDateTime ahora = ahoraUtc();
        NotificacionDisponibilidadOutbox notificacion = repository.reclamarSiguienteDisponible(
                ahora,
                EstadoNotificacionOutbox.PENDIENTE,
                EstadoNotificacionOutbox.PROCESANDO,
                EstadoNotificacionOutbox.ENVIADA,
                PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (notificacion == null) return null;
        String token = UUID.randomUUID().toString();
        notificacion.reclamar(token, ahora.plus(Duration.ofMillis(leaseMillis)));
        repository.saveAndFlush(notificacion);
        return new Claim(notificacion.getId(), notificacion.getEventoId(), notificacion.isAgotado(),
                notificacion.getIntentos(), token);
    }

    private void enviar(Claim claim) {
        try {
            int status = notifier.notificar(claim.eventoId(), claim.agotado());
            if (status != 204) {
                fallar(claim, "HTTP_" + status);
                return;
            }
            transaction.execute(statusTx -> {
                repository.findByIdForUpdate(claim.id()).ifPresent(notificacion -> {
                    if (claim.token().equals(notificacion.getTokenLease())) {
                        notificacion.marcarEnviada(ahoraUtc());
                        repository.saveAndFlush(notificacion);
                    }
                });
                return null;
            });
        } catch (RuntimeException exception) {
            fallar(claim, "TRANSPORT_OR_PROCESSING_ERROR:" + exception.getClass().getSimpleName());
        }
    }

    private void fallar(Claim claim, String error) {
        long delay = calcularBackoff(claim.intentos());
        transaction.execute(status -> {
            repository.findByIdForUpdate(claim.id()).ifPresent(notificacion -> {
                if (claim.token().equals(notificacion.getTokenLease())) {
                    notificacion.programarReintento(ahoraUtc().plus(Duration.ofMillis(delay)),
                            truncar(error, MAX_LONGITUD_ERROR));
                    repository.saveAndFlush(notificacion);
                }
            });
            return null;
        });
        LOGGER.warn("No se pudo entregar la notificación Outbox {} a MS1; se reintentará.", claim.id());
    }

    private long calcularBackoff(int intentos) {
        long delay = initialBackoffMillis;
        for (int i = 1; i < intentos && delay < maxBackoffMillis; i++) {
            delay = delay > maxBackoffMillis / 2 ? maxBackoffMillis : Math.min(maxBackoffMillis, delay * 2);
        }
        return Math.min(delay, maxBackoffMillis);
    }

    private LocalDateTime ahoraUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String truncar(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record Claim(long id, String eventoId, boolean agotado, int intentos, String token) { }
}
