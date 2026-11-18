package com.ticketya.gestionlugares.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

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

    public OutboxDispatcher(OutboxAvisoRepository outboxRepository, OutboxHttpClient httpClient, Clock outboxClock) {
        this.outboxRepository = outboxRepository;
        this.httpClient = httpClient;
        this.clock = outboxClock;
    }

    public boolean procesarSiguiente() {
        Instant ahora = clock.instant();
        Optional<AvisoOutbox> reclamado = outboxRepository.reclamarSiguiente(
                ahora, ahora.plus(LEASE), MAX_INTENTOS);
        if (reclamado.isEmpty()) {
            return false;
        }

        AvisoOutbox aviso = reclamado.orElseThrow();
        try {
            procesarRespuesta(aviso, ahora, httpClient.enviar(aviso));
        } catch (ResourceAccessException exception) {
            registrarFalloReintentable(aviso, ahora, exception.getMostSpecificCause().toString());
        }
        return true;
    }

    private void procesarRespuesta(AvisoOutbox aviso, Instant ahora, int status) {
        if (status >= 200 && status < 300) {
            aviso.setEstado(EstadoAvisoOutbox.ENTREGADO);
            aviso.setEntregadoEn(ahora);
            aviso.setUltimoError(null);
            guardarResultado(aviso, ahora);
            return;
        }

        String error = "El destino respondió HTTP " + status;
        if (status == 408 || status == 429 || status >= 500 && status < 600) {
            registrarFalloReintentable(aviso, ahora, error);
        } else if (status >= 300 && status < 500) {
            registrarFalloDefinitivo(aviso, ahora, error);
        } else {
            throw new IllegalStateException("Respuesta HTTP no clasificada: " + status);
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
}
