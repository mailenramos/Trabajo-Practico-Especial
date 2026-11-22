package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
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
import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxService;
import com.ticketya.gestionlugares.outbox.EventoPublicacionOutboxService;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;

@ExtendWith(MockitoExtension.class)
class EventoLifecycleServiceTest {

    private static final String EVENTO_ID = "evento-1";
    private static final String USER_ID = "organizador-1";

    @Mock private EventoService eventoService;
    @Mock private ReservasClient reservasClient;
    @Mock private EventoCancelacionOutboxService eventoCancelacionOutboxService;
    @Mock private EventoPublicacionOutboxService eventoPublicacionOutboxService;
    @Mock private OutboxDispatcher outboxDispatcher;
    @Mock private EventoRepository eventoRepository;
    @Mock private LugarRepository lugarRepository;
    @Mock private OutboxAvisoRepository outboxAvisoRepository;

    private EventoLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        lifecycleService = new EventoLifecycleService(eventoService, reservasClient,
                eventoCancelacionOutboxService, eventoPublicacionOutboxService, outboxDispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()));
    }

    @Test
    void publicaSoloDespuesDeInicializarYActivarVentasEnReservas() {
        Evento draft = draft();
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(draft);
        when(eventoPublicacionOutboxService.publicarConAvisoOutbox(
                EVENTO_ID, USER_ID, "organizador")).thenReturn(published);

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
        InOrder order = inOrder(reservasClient, eventoPublicacionOutboxService, outboxDispatcher);
        order.verify(reservasClient).inicializarDisponibilidad(any(), any());
        order.verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.ACTIVA);
        order.verify(eventoPublicacionOutboxService)
                .publicarConAvisoOutbox(EVENTO_ID, USER_ID, "organizador");
        order.verify(outboxDispatcher).procesarAviso("publicacion-" + EVENTO_ID);
    }

    @Test
    void fallaLaPublicacionSiNoPuedeActivarVentasEnReservas() {
        Evento draft = draft();
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(draft);
        org.mockito.Mockito.doThrow(new ReservasClientException("HTTP 503"))
                .when(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.ACTIVA);

        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.publicar(EVENTO_ID, USER_ID, "organizador"));
        assertEquals(EstadoEvento.BORRADOR, draft.getEstado());
        verify(eventoPublicacionOutboxService, never()).publicarConAvisoOutbox(any(), any(), any());
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
        verify(eventoPublicacionOutboxService, never()).publicarConAvisoOutbox(any(), any(), any());
    }

    @Test
    void reintentaPublicacionConElMismoBodyTrasTimeoutRemoto() {
        Evento draft = draft();
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(draft);
        when(eventoPublicacionOutboxService.publicarConAvisoOutbox(
                EVENTO_ID, USER_ID, "organizador")).thenReturn(published);
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
        verify(eventoPublicacionOutboxService).publicarConAvisoOutbox(EVENTO_ID, USER_ID, "organizador");
    }

    @Test
    void llamaARreservasAntesDeCambiarEstadoLocalYDejaEstadoIntactoAnteFallo() {
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(published);
        when(eventoService.suspenderVentaLocal(EVENTO_ID)).thenReturn(published);
        lifecycleService.suspenderVenta(EVENTO_ID, "admin-1", "ADMIN");
        InOrder successOrder = inOrder(reservasClient, eventoService);
        successOrder.verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.SUSPENDIDA);
        successOrder.verify(eventoService).suspenderVentaLocal(EVENTO_ID);

        when(eventoService.buscarEvento("evento-2")).thenReturn(published);
        org.mockito.Mockito.doThrow(new ReservasClientException("timeout"))
                .when(reservasClient).cambiarEstadoVenta("evento-2", EstadoVentaReservas.SUSPENDIDA);
        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.suspenderVenta("evento-2", "admin-1", "ADMIN"));
        verify(eventoService, never()).suspenderVentaLocal("evento-2");
        assertEquals(EstadoEvento.PUBLICADO, published.getEstado());
    }

    @Test
    void cambioDeEstadoDeVentaMandaLosEstadosContratados() {
        Evento evento = draft();
        evento.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(evento);
        when(eventoService.reactivarVentaLocal(EVENTO_ID)).thenReturn(evento);
        lifecycleService.reactivarVenta(EVENTO_ID, "admin-1", "ADMIN");
        verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.ACTIVA);

        when(eventoService.buscarEvento("evento-2")).thenReturn(evento);
        org.mockito.Mockito.doThrow(new ReservasClientException("timeout"))
                .when(reservasClient).cambiarEstadoVenta("evento-2", EstadoVentaReservas.ACTIVA);
        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.reactivarVenta("evento-2", "admin-1", "ADMIN"));
        verify(eventoService, never()).reactivarVentaLocal("evento-2");

        Evento cancelado = draft();
        cancelado.setEstado(EstadoEvento.CANCELADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(evento, cancelado);
        when(outboxDispatcher.procesarCancelacion("cancelacion-reservas-" + EVENTO_ID)).thenReturn(true);
        lifecycleService.cancelar(EVENTO_ID, "admin-1", "ADMIN");
        verify(outboxDispatcher).procesarCancelacion("cancelacion-reservas-" + EVENTO_ID);
    }

    @Test
    void conflictoHttpDeReservasAlSuspenderOReactivarResponde409SinCambios() throws Exception {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("http://reservas.test");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        ReservasClient cliente = new com.ticketya.gestionlugares.reservas.http.HttpReservasClient(builder.build());
        var lifecycle = new EventoLifecycleService(eventoService, cliente, eventoCancelacionOutboxService,
                eventoPublicacionOutboxService, outboxDispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.ticketya.gestionlugares.api.EventoController(eventoService, lifecycle))
                .setControllerAdvice(new com.ticketya.gestionlugares.api.ApiExceptionHandler()).build();
        Evento evento = draft();
        evento.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(evento);
        for (String estado : List.of("SUSPENDIDA", "ACTIVA")) {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                    .requestTo("http://reservas.test/disponibilidad/eventos/evento-1/venta"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                            .json("{\"estado\":\"" + estado + "\"}"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                            .withStatus(org.springframework.http.HttpStatus.CONFLICT));
        }
        for (String operacion : List.of("suspender", "reactivar")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/eventos/{id}/venta/{operacion}", EVENTO_ID, operacion)
                            .header("X-User-Id", "admin-1").header("X-User-Roles", "ADMIN"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        }
        verify(eventoService, never()).suspenderVentaLocal(EVENTO_ID);
        verify(eventoService, never()).reactivarVentaLocal(EVENTO_ID);
        assertEquals(EstadoEvento.PUBLICADO, evento.getEstado());
        server.verify();
    }

    @Test
    void admiteAdminEntreVariosRolesYRechazaAliasLegado() {
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(published);
        when(eventoService.suspenderVentaLocal(EVENTO_ID)).thenReturn(published);

        lifecycleService.suspenderVenta(EVENTO_ID, "admin-1", "CLIENTE, ADMIN");

        verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.SUSPENDIDA);
        assertThrows(ForbiddenException.class,
                () -> lifecycleService.cancelar(EVENTO_ID, "admin-1", "administrador"));
    }

    @Test
    void cancelarConFalloRemotoNoEjecutaCambioLocal() {
        Evento published = draft();
        published.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarEvento(EVENTO_ID)).thenReturn(published);
        assertThrows(ServiceUnavailableException.class,
                () -> lifecycleService.cancelar(EVENTO_ID, "admin-1", "ADMIN"));
        verify(eventoCancelacionOutboxService).registrarIntencion(any(), any(), any());
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
    void circuitoPuedeAbrirseEntreInicializacionYActivacionSinPublicar() {
        var registry = io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults();
        var circuit = registry.circuitBreaker("reservas");
        var lifecycle = new EventoLifecycleService(eventoService, reservasClient,
                eventoCancelacionOutboxService, eventoPublicacionOutboxService, outboxDispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(registry));
        Evento borrador = draft();
        when(eventoService.validarPublicacion(EVENTO_ID, USER_ID, "organizador")).thenReturn(borrador);
        org.mockito.Mockito.doAnswer(invocation -> {
            circuit.transitionToOpenState();
            return null;
        }).when(reservasClient).inicializarDisponibilidad(any(), any());

        assertThrows(ServiceUnavailableException.class,
                () -> lifecycle.publicar(EVENTO_ID, USER_ID, "organizador"));
        verify(reservasClient, never()).cambiarEstadoVenta(any(), any());
        verify(eventoPublicacionOutboxService, never()).publicarConAvisoOutbox(any(), any(), any());
        assertEquals(EstadoEvento.BORRADOR, borrador.getEstado());
    }

    @Test
    void finalizacionSigueInvocandoReservasConCircuitoAbierto() {
        var registry = io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults();
        var circuit = registry.circuitBreaker("reservas");
        circuit.transitionToOpenState();
        var lifecycle = new EventoLifecycleService(eventoService, reservasClient,
                eventoCancelacionOutboxService, eventoPublicacionOutboxService, outboxDispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(registry));
        Evento vencido = draft();
        vencido.setEstado(EstadoEvento.PUBLICADO);
        when(eventoService.buscarPendientesDeFinalizacion(any())).thenReturn(List.of(vencido));

        lifecycle.finalizarVencidos();

        verify(reservasClient).cambiarEstadoVenta(EVENTO_ID, EstadoVentaReservas.FINALIZADA);
        verify(eventoService).finalizarLocal(EVENTO_ID);
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuit.getState());
        assertEquals(0, circuit.getMetrics().getNumberOfBufferedCalls());
    }

    @Test
    void conflictoDeVersionConcurrenteAlRegistrarCancelacionDevuelve503() {
        Evento primeraLectura = draft();
        primeraLectura.setEstado(EstadoEvento.PUBLICADO);
        Evento lecturaParaCancelar = draft();
        lecturaParaCancelar.setEstado(EstadoEvento.PUBLICADO);
        when(eventoRepository.findById(EVENTO_ID))
                .thenReturn(java.util.Optional.of(primeraLectura), java.util.Optional.of(lecturaParaCancelar));
        when(eventoRepository.save(any(Evento.class)))
                .thenThrow(new OptimisticLockingFailureException("version desactualizada"));
        EventoService versionedEventService = new EventoService(eventoRepository, lugarRepository,
                org.mockito.Mockito.mock(CatalogoCacheInvalidador.class), outboxAvisoRepository,
                new ComprasProperties(), Clock.systemUTC());
        EventoCancelacionOutboxService cancellationService = new EventoCancelacionOutboxService(
                versionedEventService, outboxAvisoRepository, new ComprasProperties(),
                new com.ticketya.gestionlugares.reservas.http.ReservasProperties(),
                new com.ticketya.gestionlugares.filas.http.FilasProperties());
        EventoLifecycleService concurrentLifecycle = new EventoLifecycleService(versionedEventService, reservasClient,
                cancellationService, eventoPublicacionOutboxService, outboxDispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()));

        assertThrows(ServiceUnavailableException.class,
                () -> concurrentLifecycle.cancelar(EVENTO_ID, "admin-1", "ADMIN"));
        verify(reservasClient, never()).cambiarEstadoVenta(any(), any());
        verify(eventoRepository).save(any(Evento.class));
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
                java.util.Optional.of(leidoPorCallback),
                java.util.Optional.of(leidoPorCallback));
        when(eventoRepository.save(any(Evento.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventoService realEventService = new EventoService(eventoRepository, lugarRepository,
                org.mockito.Mockito.mock(CatalogoCacheInvalidador.class), outboxAvisoRepository,
                new ComprasProperties(), Clock.systemUTC());
        EventoCancelacionOutboxService cancellationService = new EventoCancelacionOutboxService(
                realEventService, outboxAvisoRepository, new ComprasProperties(),
                new com.ticketya.gestionlugares.reservas.http.ReservasProperties(),
                new com.ticketya.gestionlugares.filas.http.FilasProperties());
        EventoLifecycleService concurrentLifecycle = new EventoLifecycleService(realEventService, reservasClient,
                cancellationService, eventoPublicacionOutboxService, outboxDispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()));
        when(outboxAvisoRepository.confirmarReclamo(any(), any())).thenReturn(true);
        when(outboxDispatcher.procesarCancelacion("cancelacion-reservas-" + EVENTO_ID)).thenAnswer(invocation -> {
            realEventService.aplicarDisponibilidadLocal(EVENTO_ID, true);
            com.ticketya.gestionlugares.domain.AvisoOutbox trabajo = new com.ticketya.gestionlugares.domain.AvisoOutbox();
            trabajo.setEventoId(EVENTO_ID);
            trabajo.setMotivoCancelacion("motivo");
            cancellationService.confirmar(trabajo, Instant.now());
            return true;
        });

        Evento result = concurrentLifecycle.cancelar(EVENTO_ID, "admin-1", "ADMIN");

        assertEquals(EstadoEvento.CANCELADO, result.getEstado());
        verify(eventoRepository, org.mockito.Mockito.times(3)).save(any(Evento.class));
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
