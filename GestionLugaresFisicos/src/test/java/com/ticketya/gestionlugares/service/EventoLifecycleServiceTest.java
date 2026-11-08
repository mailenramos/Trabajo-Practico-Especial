package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;

@ExtendWith(MockitoExtension.class)
class EventoLifecycleServiceTest {

    private static final String EVENTO_ID = "evento-1";
    private static final String USER_ID = "organizador-1";

    @Mock private EventoService eventoService;
    @Mock private ReservasClient reservasClient;
    @Mock private EventoRepository eventoRepository;
    @Mock private LugarRepository lugarRepository;

    private EventoLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        lifecycleService = new EventoLifecycleService(eventoService, reservasClient);
    }

    @Test
    void publicaSoloDespuesDeInicializarYDerivaAsientosSinPersistirlos() {
        Evento draft = draft();
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(draft);
        when(eventoService.confirmarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(published);

        Evento result = lifecycleService.publicar(EVENTO_ID, USER_ID, "organizador");

        assertEquals(EstadoEvento.PUBLICADO, result.getEstado());
        ArgumentCaptor<InicializarDisponibilidadRequest> request =
                ArgumentCaptor.forClass(InicializarDisponibilidadRequest.class);
        verify(reservasClient).inicializarDisponibilidad(org.mockito.ArgumentMatchers.eq(EVENTO_ID), request.capture());
        assertEquals(4, request.getValue().limiteEntradasPorCliente());
        assertEquals(2, request.getValue().sectores().get(0).capacidadTotal());
        assertEquals(List.of(new com.ticketya.gestionlugares.reservas.dto.AsientoReservas(1, 1),
                new com.ticketya.gestionlugares.reservas.dto.AsientoReservas(1, 2)),
                request.getValue().sectores().get(0).asientos());
        assertEquals(List.of(), request.getValue().sectores().get(1).asientos());
        InOrder order = inOrder(reservasClient, eventoService);
        order.verify(reservasClient).inicializarDisponibilidad(any(), any());
        order.verify(eventoService).confirmarPublicacion(EVENTO_ID, USER_ID, "organizador");
    }

    @Test
    void fallaLaPublicacionSinCambiarEstadoSiReservasFalla() {
        Evento draft = draft();
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(draft);
        org.mockito.Mockito.doThrow(new ReservasClientException("HTTP 409"))
                .when(reservasClient).inicializarDisponibilidad(any(), any());

        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.publicar(EVENTO_ID, USER_ID, "organizador"));
        assertEquals(EstadoEvento.BORRADOR, draft.getEstado());
        verify(eventoService, never()).confirmarPublicacion(any(), any(), any());
    }

    @Test
    void reintentaPublicacionConElMismoBodyTrasTimeoutRemoto() {
        Evento draft = draft();
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(draft);
        when(eventoService.confirmarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(published);
        org.mockito.Mockito.doThrow(new ReservasClientException("timeout")).doNothing()
                .when(reservasClient).inicializarDisponibilidad(any(), any());

        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.publicar(EVENTO_ID, USER_ID, "organizador"));
        assertEquals(EstadoEvento.BORRADOR, draft.getEstado());
        lifecycleService.publicar(EVENTO_ID, USER_ID, "organizador");

        ArgumentCaptor<InicializarDisponibilidadRequest> requests =
                ArgumentCaptor.forClass(InicializarDisponibilidadRequest.class);
        verify(reservasClient, org.mockito.Mockito.times(2))
                .inicializarDisponibilidad(org.mockito.ArgumentMatchers.eq(EVENTO_ID), requests.capture());
        assertEquals(requests.getAllValues().get(0), requests.getAllValues().get(1));
        verify(eventoService).confirmarPublicacion(EVENTO_ID, USER_ID, "organizador");
    }

    @Test
    void llamaARreservasAntesDeCambiarEstadoLocalYDejaEstadoIntactoAnteFallo() {
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(published);
        when(eventoService.suspenderVentaLocal(EVENTO_ID)).thenReturn(published);
        lifecycleService.suspenderVenta(EVENTO_ID, "admin-1", "administrador");
        InOrder successOrder = inOrder(reservasClient, eventoService);
        successOrder.verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.SUSPENDIDA);
        successOrder.verify(eventoService).suspenderVentaLocal(EVENTO_ID);

        when(eventoService.buscarEvento("evento-2")).thenReturn(published);
        org.mockito.Mockito.doThrow(new ReservasClientException("timeout"))
                .when(reservasClient).cambiarEstadoVenta("evento-2", EstadoVentaReservas.SUSPENDIDA);
        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.suspenderVenta("evento-2", "admin-1", "administrador"));
        verify(eventoService, never()).suspenderVentaLocal("evento-2");
        assertEquals(EstadoEvento.PUBLICADO, published.getEstado());
    }

    @Test
    void cambioDeEstadoDeVentaMandaLosEstadosContratados() {
        Evento evento = draft();
        evento.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(evento);
        when(eventoService.reactivarVentaLocal(EVENTO_ID)).thenReturn(evento);
        lifecycleService.reactivarVenta(EVENTO_ID, "admin-1", "administrador");
        verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.ACTIVA);

        when(eventoService.cancelarLocal(EVENTO_ID)).thenReturn(evento);
        lifecycleService.cancelar(EVENTO_ID, "admin-1", "administrador");
        verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.CANCELADA);
    }

    @Test
    void cancelarConFalloRemotoNoEjecutaCambioLocal() {
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(published);
        org.mockito.Mockito.doThrow(new ReservasClientException("timeout"))
                .when(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.CANCELADA);

        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.cancelar(EVENTO_ID, "admin-1", "administrador"));
        verify(eventoService, never()).cancelarLocal(any());
        assertEquals(EstadoEvento.PUBLICADO, published.getEstado());
    }

    @Test
    void finalizaVencidosSoloTrasConfirmacionRemotaYDejaPendienteSiFalla() {
        Evento vencido = draft();
        vencido.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarPendientesDeFinalizacion(any())).thenReturn(List.of(vencido));
        lifecycleService.finalizarVencidos();
        InOrder successOrder = inOrder(reservasClient, eventoService);
        successOrder.verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.FINALIZADA);
        successOrder.verify(eventoService).finalizarLocal(EVENTO_ID);

        org.mockito.Mockito.reset(reservasClient, eventoService);
        when(eventoService.buscarPendientesDeFinalizacion(any())).thenReturn(List.of(vencido));
        org.mockito.Mockito.doThrow(new ReservasClientException("timeout"))
                .when(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.FINALIZADA);
        lifecycleService.finalizarVencidos();
        verify(eventoService, never()).finalizarLocal(any());
    }

    @Test
    void conflictoDeVersionConcurrenteAlCancelarSeConservaComoConflicto() {
        Evento primeraLectura = draft();
        primeraLectura.setEstado(EstadoEvento.PUBLICADO);
        Evento lecturaParaCancelar = draft();
        lecturaParaCancelar.setEstado(EstadoEvento.PUBLICADO);
        when(eventoRepository.findById(EVENTO_ID))
                .thenReturn(java.util.Optional.of(primeraLectura), java.util.Optional.of(lecturaParaCancelar));
        when(eventoRepository.save(any(Evento.class)))
                .thenThrow(new OptimisticLockingFailureException("version desactualizada"));
        EventoService versionedEventService = new EventoService(eventoRepository, lugarRepository);
        EventoLifecycleService concurrentLifecycle = new EventoLifecycleService(versionedEventService, reservasClient);

        assertThrows(ConflictException.class,
                () -> concurrentLifecycle.cancelar(EVENTO_ID, "admin-1", "administrador"));
        InOrder order = inOrder(reservasClient, eventoRepository);
        order.verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.CANCELADA);
        order.verify(eventoRepository).save(any(Evento.class));
    }

    @Test
    void callbackDeAgotadoDuranteCancelacionSeAplicaAntesDelEstadoCancelado() {
        Evento leidoAlValidar = draft();
        leidoAlValidar.setEstado(EstadoEvento.PUBLICADO);
        Evento leidoPorCallback = draft();
        leidoPorCallback.setEstado(EstadoEvento.PUBLICADO);
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(
                java.util.Optional.of(leidoAlValidar),
                java.util.Optional.of(leidoPorCallback),
                java.util.Optional.of(leidoPorCallback));
        when(eventoRepository.save(any(Evento.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventoService realEventService = new EventoService(eventoRepository, lugarRepository);
        EventoLifecycleService concurrentLifecycle = new EventoLifecycleService(realEventService, reservasClient);
        org.mockito.Mockito.doAnswer(invocation -> {
            realEventService.aplicarDisponibilidadLocal(EVENTO_ID, true);
            return null;
        }).when(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.CANCELADA);

        Evento result = concurrentLifecycle.cancelar(EVENTO_ID, "admin-1", "administrador");

        assertEquals(EstadoEvento.CANCELADO, result.getEstado());
        verify(eventoRepository, org.mockito.Mockito.times(2)).save(any(Evento.class));
    }

    private static Evento draft() {
        Evento evento = Evento.borrador(USER_ID, "lugar-1", "CABA", "Festival", "Descripción", "MUSICA",
                List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("numerado-1", "Platea", TipoSector.NUMERADO,
                                1, 2, null, new BigDecimal("100")),
                        new EventoSector("general-1", "Campo", TipoSector.NO_NUMERADO,
                                null, null, 500, new BigDecimal("50"))));
        evento.setId(EVENTO_ID);
        return evento;
    }
}
