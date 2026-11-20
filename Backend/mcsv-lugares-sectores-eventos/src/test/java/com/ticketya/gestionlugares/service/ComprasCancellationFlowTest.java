package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxService;
import com.ticketya.gestionlugares.outbox.EventoPublicacionOutboxService;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.service.EventoService;

@ExtendWith(MockitoExtension.class)
class ComprasCancellationFlowTest {

    private static final String EVENT_ID = "evento-1";
    @Mock private EventoService eventoService;
    @Mock private ReservasClient reservasClient;
    @Mock private EventoCancelacionOutboxService eventoCancelacionOutboxService;
    @Mock private EventoPublicacionOutboxService eventoPublicacionOutboxService;
    @Mock private OutboxDispatcher outboxDispatcher;

    private EventoLifecycleService lifecycle;
    private Evento evento;
    private Evento cancelado;

    @BeforeEach
    void setUp() {
        lifecycle = new EventoLifecycleService(eventoService, reservasClient,
                eventoCancelacionOutboxService, eventoPublicacionOutboxService, outboxDispatcher);
        evento = Evento.borrador("org", "lugar", "CABA", "Show", "Desc", "MUSICA", List.of("A"),
                Instant.parse("2027-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("sector", "Platea", TipoSector.NUMERADO, 1, 1, null, null)));
        evento.setId(EVENT_ID);
        evento.setEstado(EstadoEvento.PUBLICADO);
        cancelado = Evento.borrador("org", "lugar", "CABA", "Show", "Desc", "MUSICA", List.of("A"),
                Instant.parse("2027-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("sector", "Platea", TipoSector.NUMERADO, 1, 1, null, null)));
        cancelado.setId(EVENT_ID);
        cancelado.setEstado(EstadoEvento.CANCELADO);
    }

    @Test
    void guardaCancelacionAntesDeAvisarYUsaMotivoPredeterminado() {
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento, cancelado);
        when(outboxDispatcher.procesarCancelacion("cancelacion-reservas-" + EVENT_ID)).thenReturn(true);

        Evento result = lifecycle.cancelar(EVENT_ID, null, "admin", "ADMIN");

        assertEquals(EstadoEvento.CANCELADO, result.getEstado());
        InOrder order = inOrder(eventoCancelacionOutboxService, outboxDispatcher);
        order.verify(eventoCancelacionOutboxService).registrarIntencion(
                eq(EVENT_ID), eq("Cancelación del evento por el administrador"), any());
        order.verify(outboxDispatcher).procesarCancelacion("cancelacion-reservas-" + EVENT_ID);
    }

    @Test
    void laCancelacionNuevaIntentaEntregaInmediataPorDispatcher() {
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento, cancelado);
        when(outboxDispatcher.procesarCancelacion("cancelacion-reservas-" + EVENT_ID)).thenReturn(true);

        Evento result = lifecycle.cancelar(EVENT_ID, "Cierre preventivo", "admin", "ADMIN");

        assertEquals(EstadoEvento.CANCELADO, result.getEstado());
        verify(outboxDispatcher).procesarCancelacion("cancelacion-reservas-" + EVENT_ID);
    }

    @Test
    void unaSegundaCancelacionRespondeConflictoYSinSegundoAviso() {
        evento.setEstado(EstadoEvento.CANCELADO);
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento);

        assertThrows(ConflictException.class,
                () -> lifecycle.cancelar(EVENT_ID, "otro motivo", "admin", "ADMIN"));

        verify(reservasClient, never()).cambiarEstadoVenta(any(), any());
        verify(eventoCancelacionOutboxService, never()).registrarIntencion(any(), any(), any());
        verify(outboxDispatcher, never()).procesarAviso(any());
        verify(outboxDispatcher, never()).procesarCancelacion(any());
    }

    @Test
    void noPermiteCancelarEventoFinalizado() {
        evento.setEstado(EstadoEvento.FINALIZADO);
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento);

        assertThrows(ConflictException.class,
                () -> lifecycle.cancelar(EVENT_ID, "otro motivo", "admin", "ADMIN"));

        verify(reservasClient, never()).cambiarEstadoVenta(any(), any());
        verify(eventoCancelacionOutboxService, never()).registrarIntencion(any(), any(), any());
        verify(outboxDispatcher, never()).procesarAviso(any());
        verify(outboxDispatcher, never()).procesarCancelacion(any());
    }

}
