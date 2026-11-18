package com.ticketya.gestionlugares.outbox;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@Component
public class OutboxScheduler {

    public static final int MAX_AVISOS_POR_PASADA = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxAvisoRepository outboxRepository;
    private final OutboxDispatcher dispatcher;
    private final Clock clock;

    public OutboxScheduler(OutboxAvisoRepository outboxRepository, OutboxDispatcher dispatcher, Clock outboxClock) {
        this.outboxRepository = outboxRepository;
        this.dispatcher = dispatcher;
        this.clock = outboxClock;
    }

    @Scheduled(fixedDelayString = "${outbox.dispatcher.interval:60000}",
            initialDelayString = "${outbox.dispatcher.interval:60000}")
    public void procesarPendientes() {
        Instant ahora = clock.instant();
        long agotados = outboxRepository.marcarAvisosAgotados(ahora, OutboxDispatcher.MAX_INTENTOS);
        if (agotados > 0) {
            LOGGER.error("Se marcaron {} avisos outbox como ERROR_FINAL por intentos agotados", agotados);
        }
        for (int procesados = 0; procesados < MAX_AVISOS_POR_PASADA; procesados++) {
            if (!dispatcher.procesarSiguiente()) break;
        }
    }
}
