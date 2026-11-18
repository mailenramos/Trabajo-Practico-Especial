package com.ticketya.gestionlugares.outbox;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

    @Mock
    private OutboxAvisoRepository outboxRepository;

    @Mock
    private OutboxDispatcher dispatcher;

    @Test
    void cadaPasadaMarcaIntentosAgotadosAntesDeDespachar() {
        Instant ahora = Instant.parse("2026-11-18T18:00:00Z");
        OutboxScheduler scheduler = new OutboxScheduler(outboxRepository, dispatcher,
                Clock.fixed(ahora, ZoneOffset.UTC));
        when(dispatcher.procesarSiguiente()).thenReturn(false);

        scheduler.procesarPendientes();

        InOrder orden = inOrder(outboxRepository, dispatcher);
        orden.verify(outboxRepository).marcarAvisosAgotados(ahora, OutboxDispatcher.MAX_INTENTOS);
        orden.verify(dispatcher).procesarSiguiente();
    }

    @Test
    void limitaCadaPasadaACienAvisos() {
        OutboxScheduler scheduler = new OutboxScheduler(outboxRepository, dispatcher,
                Clock.fixed(Instant.parse("2026-11-18T18:00:00Z"), ZoneOffset.UTC));
        when(dispatcher.procesarSiguiente()).thenReturn(true);

        scheduler.procesarPendientes();

        verify(dispatcher, times(OutboxScheduler.MAX_AVISOS_POR_PASADA)).procesarSiguiente();
    }
}
