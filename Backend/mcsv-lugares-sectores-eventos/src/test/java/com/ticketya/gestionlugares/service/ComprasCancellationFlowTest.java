package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ticketya.gestionlugares.compras.ComprasClient;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.service.EventoService;

@ExtendWith(MockitoExtension.class)
class ComprasCancellationFlowTest {

    private static final String EVENT_ID = "evento-1";
    @Mock private EventoService eventoService;
    @Mock private ReservasClient reservasClient;
    @Mock private ComprasClient comprasClient;

    private EventoLifecycleService lifecycle;
    private Evento evento;
    private Evento cancelado;

    @BeforeEach
    void setUp() {
        lifecycle = new EventoLifecycleService(eventoService, reservasClient, comprasClient);
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
        cancelado.setAvisoCancelacionPendiente(true);
    }

    @Test
    void guardaCancelacionAntesDeAvisarYUsaMotivoPredeterminado() {
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento);
        when(eventoService.cancelarLocal(eq(EVENT_ID), any(), any())).thenAnswer(invocation -> {
            String reason = invocation.getArgument(1);
            Instant canceledAt = invocation.getArgument(2);
            assertEquals("Cancelación del evento por el administrador", reason);
            assertTrue(canceledAt.isBefore(Instant.now().plusSeconds(1)));
            assertTrue(canceledAt.isAfter(Instant.now().minusSeconds(10)));
            return cancelado(reason, canceledAt, true);
        });
        when(eventoService.marcarAvisoCancelacionEntregado(EVENT_ID))
                .thenReturn(cancelado("Cancelación del evento por el administrador", Instant.now(), false));

        Evento result = lifecycle.cancelar(EVENT_ID, null, "admin", "administrador");

        assertEquals(EstadoEvento.CANCELADO, result.getEstado());
        assertFalse(result.isAvisoCancelacionPendiente());
        ArgumentCaptor<Instant> sentAt = ArgumentCaptor.forClass(Instant.class);
        verify(comprasClient).notificarCancelacion(eq(EVENT_ID),
                eq("Cancelación del evento por el administrador"), sentAt.capture());
        verify(reservasClient).cambiarEstadoVenta(EVENT_ID, EstadoVentaReservas.CANCELADA);
        assertTrue(sentAt.getValue().isBefore(Instant.now().plusSeconds(1)));
        InOrder order = inOrder(reservasClient, eventoService, comprasClient);
        order.verify(reservasClient).cambiarEstadoVenta(EVENT_ID, EstadoVentaReservas.CANCELADA);
        order.verify(eventoService).cancelarLocal(eq(EVENT_ID), eq("Cancelación del evento por el administrador"), any());
        order.verify(comprasClient).notificarCancelacion(eq(EVENT_ID),
                eq("Cancelación del evento por el administrador"), any());
        order.verify(eventoService).marcarAvisoCancelacionEntregado(EVENT_ID);
    }

    @Test
    void falloDeComprasNoRevierteCancelacionYDejaAvisoPendiente() {
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento);
        when(eventoService.cancelarLocal(eq(EVENT_ID), any(), any())).thenAnswer(invocation -> {
            String reason = invocation.getArgument(1);
            Instant canceledAt = invocation.getArgument(2);
            return cancelado(reason, canceledAt, true);
        });
        org.mockito.Mockito.doThrow(new RuntimeException("Compras caída"))
                .when(comprasClient).notificarCancelacion(any(), any(), any());

        Evento result = lifecycle.cancelar(EVENT_ID, "Cierre preventivo", "admin", "administrador");

        assertEquals(EstadoEvento.CANCELADO, result.getEstado());
        assertTrue(result.isAvisoCancelacionPendiente());
        verify(eventoService, never()).marcarAvisoCancelacionEntregado(any());
    }

    @Test
    void procesoReenviaAvisosPendientesYMarcaElAceptado() {
        Evento pending = cancelado("Cierre", Instant.parse("2026-11-06T18:00:00Z"), true);
        when(eventoService.buscarAvisosCancelacionPendientes()).thenReturn(List.of(pending));
        org.mockito.Mockito.doThrow(new RuntimeException("Compras caída")).doNothing()
                .when(comprasClient).notificarCancelacion(EVENT_ID, "Cierre", Instant.parse("2026-11-06T18:00:00Z"));

        lifecycle.reenviarAvisosCancelacionPendientes();
        lifecycle.reenviarAvisosCancelacionPendientes();

        verify(comprasClient, org.mockito.Mockito.times(2))
                .notificarCancelacion(EVENT_ID, "Cierre", Instant.parse("2026-11-06T18:00:00Z"));
        verify(eventoService).marcarAvisoCancelacionEntregado(EVENT_ID);
    }

    @Test
    void unaSegundaCancelacionRespondeConflictoYSinSegundoAviso() {
        evento.setEstado(EstadoEvento.CANCELADO);
        when(eventoService.buscarEvento(EVENT_ID)).thenReturn(evento);

        assertThrows(ConflictException.class,
                () -> lifecycle.cancelar(EVENT_ID, "otro motivo", "admin", "administrador"));

        verify(reservasClient, never()).cambiarEstadoVenta(any(), any());
        verify(comprasClient, never()).notificarCancelacion(any(), any(), any());
        verify(eventoService, never()).cancelarLocal(any(), any(), any());
    }

    private static Evento cancelado(String motivo, Instant cuando, boolean pendiente) {
        Evento event = Evento.borrador("org", "lugar", "CABA", "Show", "Desc", "MUSICA", List.of("A"),
                Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        event.setId(EVENT_ID);
        event.setEstado(EstadoEvento.CANCELADO);
        event.setMotivoCancelacion(motivo);
        event.setCanceladoEn(cuando);
        event.setAvisoCancelacionPendiente(pendiente);
        return event;
    }
}
