package com.ticketya.gestionlugares.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.client.ResourceAccessException;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;
import com.ticketya.gestionlugares.service.ConflictException;

@Service
public class OutboxDispatcher {

    public static final int MAX_INTENTOS = 8;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration ESPERA_BASE = Duration.ofSeconds(30);
    private static final Duration ESPERA_MAXIMA = Duration.ofMinutes(15);
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxAvisoRepository outboxRepository;
    private final OutboxHttpClient httpClient;
    private final Clock clock;
    private final ServicioToken servicioToken;
    private final EventoCancelacionOutboxService cancelacionService;

    public OutboxDispatcher(OutboxAvisoRepository outboxRepository, OutboxHttpClient httpClient, Clock outboxClock,
            ServicioToken servicioToken, EventoCancelacionOutboxService cancelacionService) {
        this.outboxRepository = outboxRepository;
        this.httpClient = httpClient;
        this.clock = outboxClock;
        this.servicioToken = servicioToken;
        this.cancelacionService = cancelacionService;
    }

    public boolean puedeProcesar() {
        return servicioToken.disponible();
    }

    public boolean procesarSiguiente() {
        if (!puedeProcesar()) return false;
        Instant ahora = clock.instant();
        Optional<AvisoOutbox> reclamado = outboxRepository.reclamarSiguiente(
                ahora, ahora.plus(LEASE), MAX_INTENTOS);
        return procesar(reclamado, ahora) != Resultado.NO_RECLAMADO;
    }

    public boolean procesarAviso(String avisoId) {
        if (!puedeProcesar()) return false;
        Instant ahora = clock.instant();
        Optional<AvisoOutbox> reclamado = outboxRepository.reclamarPorId(
                avisoId, ahora, ahora.plus(LEASE), MAX_INTENTOS);
        return procesar(reclamado, ahora) != Resultado.NO_RECLAMADO;
    }

    public boolean procesarCancelacion(String avisoId) {
        if (!puedeProcesar()) return false;
        Instant ahora = clock.instant();
        Optional<AvisoOutbox> reclamado = outboxRepository.reclamarPorId(
                avisoId, ahora, ahora.plus(LEASE), MAX_INTENTOS);
        return procesar(reclamado, ahora) == Resultado.CANCELACION_CONFIRMADA;
    }

    private Resultado procesar(Optional<AvisoOutbox> reclamado, Instant ahora) {
        if (reclamado.isEmpty()) return Resultado.NO_RECLAMADO;
        AvisoOutbox aviso = reclamado.orElseThrow();
        try (Correlacion.Contexto contexto = Correlacion.usar(aviso.getCorrelationId())) {
            try {
                if (procesarRespuesta(aviso, ahora, httpClient.enviar(aviso))) {
                    return Resultado.CANCELACION_CONFIRMADA;
                }
            } catch (ResourceAccessException exception) {
                registrarFalloReintentable(aviso, ahora,
                        servicioToken.ocultarEn(exception.getMostSpecificCause().toString()));
            }
        }
        return Resultado.PROCESADO;
    }

    private boolean procesarRespuesta(AvisoOutbox aviso, Instant ahora, int status) {
        if ("CANCELACION_RESERVAS".equals(aviso.getTipo()) && status >= 200 && status < 300) {
            if (status != 204) {
                registrarFalloReintentable(aviso, clock.instant(), "Reservas respondió HTTP " + status);
                return false;
            }
            try {
                if (cancelacionService.confirmar(aviso, clock.instant())) {
                    intentarNotificacion("cancelacion-" + aviso.getEventoId());
                    intentarNotificacion("cancelacion-fila-" + aviso.getEventoId());
                    return true;
                } else {
                    LOGGER.warn("No se confirmó la cancelación: reclamo vencido para {}", aviso.getId());
                }
            } catch (DataAccessException | TransactionException | ConflictException exception) {
                LOGGER.error("No se pudo confirmar la cancelación del aviso {}; se recuperará por lease: {}",
                        aviso.getId(), exception.getClass().getSimpleName());
            }
            return false;
        }

        if (status >= 200 && status < 300) {
            aviso.setEstado(EstadoAvisoOutbox.ENTREGADO);
            aviso.setEntregadoEn(ahora);
            aviso.setUltimoError(null);
            guardarResultado(aviso, ahora);
            return false;
        }

        String error = "El destino respondió HTTP " + status;
        if (status == 408 || status == 429 || status >= 500 && status < 600) {
            registrarFalloReintentable(aviso, ahora, error);
        } else if (status >= 300 && status < 500) {
            registrarFalloDefinitivo(aviso, ahora, error);
        } else {
            throw new IllegalStateException("Respuesta HTTP no clasificada: " + status);
        }
        return false;
    }

    private void intentarNotificacion(String avisoId) {
        try {
            procesarAviso(avisoId);
        } catch (DataAccessException | TransactionException exception) {
            LOGGER.error("No se pudo intentar el aviso {}; el scheduler podrá recuperarlo: {}",
                    avisoId, exception.getClass().getSimpleName());
        }
    }

    private void registrarFalloReintentable(AvisoOutbox aviso, Instant ahora, String error) {
        aviso.setUltimoError(error);
        if (aviso.getIntentos() >= MAX_INTENTOS) {
            aviso.setEstado(EstadoAvisoOutbox.ERROR_FINAL);
            LOGGER.error("Aviso outbox {} agotó sus intentos: {}", aviso.getId(), error);
        } else {
            aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
            aviso.setProximoIntento(ahora.plus(esperaParaIntento(aviso.getIntentos())));
            LOGGER.warn("Falló la entrega del aviso outbox {}; reintento programado: {}",
                    aviso.getId(), error);
        }
        guardarResultado(aviso, ahora);
    }

    private void registrarFalloDefinitivo(AvisoOutbox aviso, Instant ahora, String error) {
        aviso.setEstado(EstadoAvisoOutbox.ERROR_FINAL);
        aviso.setUltimoError(error);
        LOGGER.error("Error definitivo al entregar el aviso outbox {}: {}", aviso.getId(), error);
        guardarResultado(aviso, ahora);
    }

    private void guardarResultado(AvisoOutbox aviso, Instant ahora) {
        if ("CANCELACION_RESERVAS".equals(aviso.getTipo())) {
            Instant leaseHasta = aviso.getLeaseHasta();
            if (!outboxRepository.guardarResultadoReclamo(aviso, leaseHasta, clock.instant())) {
                LOGGER.warn("No se guardó el resultado del reclamo vencido {}", aviso.getId());
            }
            return;
        }
        aviso.setActualizadoEn(ahora);
        aviso.setLeaseHasta(null);
        outboxRepository.save(aviso);
    }

    static Duration esperaParaIntento(int numeroIntento) {
        if (numeroIntento < 1) {
            throw new IllegalArgumentException("numeroIntento debe ser mayor que cero");
        }
        Duration espera = ESPERA_BASE;
        for (int intento = 1; intento < numeroIntento && espera.compareTo(ESPERA_MAXIMA) < 0; intento++) {
            espera = espera.multipliedBy(2);
            if (espera.compareTo(ESPERA_MAXIMA) > 0) {
                espera = ESPERA_MAXIMA;
            }
        }
        return espera;
    }

    private enum Resultado {
        NO_RECLAMADO, PROCESADO, CANCELACION_CONFIRMADA
    }
}
