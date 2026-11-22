package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.config.CatalogoCacheConfiguration;
import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxService;
import com.ticketya.gestionlugares.outbox.EventoPublicacionOutboxService;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.outbox.OutboxHttpClient;
import com.ticketya.gestionlugares.outbox.OutboxScheduler;
import com.ticketya.gestionlugares.outbox.RestClientOutboxHttpClient;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.http.HttpReservasClient;
import com.ticketya.gestionlugares.service.EventoLifecycleService;
import com.ticketya.gestionlugares.service.EventoService;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;

@SpringBootTest(properties = {
        "outbox.dispatcher.interval=3600000",
        "eventos.finalizacion.interval=3600000",
        "servicio.token=token-prueba"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CancelacionRecuperableIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_cancelacion_recuperable"));
    }

    @Autowired private MockMvc mvc;
    @Autowired private EventoRepository eventos;
    @Autowired private OutboxAvisoRepository outbox;
    @Autowired private MongoTemplate mongo;
    @Autowired private EventoCancelacionOutboxService cancelaciones;
    @Autowired private EventoLifecycleService lifecycle;
    @Autowired private EventoService eventoService;
    @Autowired private EventoPublicacionOutboxService publicaciones;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private OutboxScheduler scheduler;
    @Autowired private CaffeineCacheManager cacheManager;
    @Autowired private TransactionTemplate transaccion;
    @Autowired private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitos;
    @MockBean private ReservasClient reservas;
    @MockBean private OutboxHttpClient http;
    @MockBean(name = "outboxClock") private Clock reloj;

    private final AtomicReference<Instant> ahora = new AtomicReference<>();

    @BeforeEach
    void preparar() {
        circuitos.circuitBreaker("reservas").reset();
        ahora.set(Instant.now().plusSeconds(60));
        when(reloj.instant()).thenAnswer(invocation -> ahora.get());
        outbox.deleteAll();
        eventos.deleteAll();
        cache().clear();
    }

    @Test
    void cancelacionYDispatcherNoDependenDelCircuitoDeReservas() throws Exception {
        var circuito = circuitos.circuitBreaker("reservas");
        circuito.transitionToOpenState();
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(204);

        mvc.perform(cancelar(evento.getId(), "Motivo")).andExpect(status().isOk());

        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        verify(http, times(3)).enviar(any(AvisoOutbox.class));
        assertEquals(EstadoAvisoOutbox.ENTREGADO, trabajo(evento).getEstado());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuito.getState());
        assertEquals(0, circuito.getMetrics().getNumberOfBufferedCalls());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelacionExitosaCreaUnaVezAmbosAvisosConOSinFila(boolean requiereFila) throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, requiereFila, false);
        when(http.enviar(any(AvisoOutbox.class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return 204;
        });

        mvc.perform(cancelar(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cancelado"))
                .andExpect(jsonPath("$.cancelacionPendiente").doesNotExist());

        Evento guardado = eventos.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.CANCELADO, guardado.getEstado());
        assertFalse(guardado.isCancelacionPendiente());
        assertEquals("Cierre preventivo", guardado.getMotivoCancelacion());
        assertNotNull(guardado.getCanceladoEn());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, trabajo(evento).getEstado());
        assertEquals(Map.of("estado", "CANCELADA"), trabajo(evento).getPayload());
        assertEquals("PUT", trabajo(evento).getMetodoHttp());
        assertEquals("traza-cancelacion", trabajo(evento).getCorrelationId());
        assertEquals(Set.of("cancelacion-reservas-" + evento.getId(), "cancelacion-" + evento.getId(),
                "cancelacion-fila-" + evento.getId()),
                outbox.findAll().stream().map(AvisoOutbox::getId).collect(Collectors.toSet()));
        AvisoOutbox compras = aviso("cancelacion-" + evento.getId());
        AvisoOutbox fila = aviso("cancelacion-fila-" + evento.getId());
        assertEquals("Cierre preventivo", compras.getPayload().get("motivo"));
        assertEquals(guardado.getCanceladoEn().toEpochMilli(),
                Instant.parse((String) compras.getPayload().get("canceladoEn")).toEpochMilli());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, compras.getEstado());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, fila.getEstado());
        assertEquals("traza-cancelacion", fila.getCorrelationId());
        assertNull(fila.getPayload());
        verify(http, times(3)).enviar(any(AvisoOutbox.class));
    }

    @Test
    void repetirEventoCanceladoDevuelve409SinAvisosNuevos() throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(204);
        mvc.perform(cancelar(evento.getId(), "Primer motivo")).andExpect(status().isOk());

        mvc.perform(cancelar(evento.getId(), "Otro motivo")).andExpect(status().isConflict());
        assertEquals(3, outbox.count());
        assertEquals("Primer motivo", eventos.findById(evento.getId()).orElseThrow().getMotivoCancelacion());
        verify(http, times(3)).enviar(any(AvisoOutbox.class));
    }

    @Test
    void cancelarFinalizadoDevuelve409SinCrearTrabajoNiAvisos() throws Exception {
        Evento evento = evento(EstadoEvento.FINALIZADO, false, false);
        mvc.perform(cancelar(evento.getId(), "Motivo")).andExpect(status().isConflict());
        assertEquals(0, outbox.count());
        verify(http, never()).enviar(any());
    }

    @Test
    void intencionPendienteDevuelve503SinReemplazarMotivoNiDuplicar() throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo original");

        mvc.perform(cancelar(evento.getId(), "Otro motivo")).andExpect(status().isServiceUnavailable());
        assertTrue(eventos.findById(evento.getId()).orElseThrow().isCancelacionPendiente());
        assertEquals("Motivo original", trabajo(evento).getMotivoCancelacion());
        assertEquals(1, outbox.count());
        verify(http, never()).enviar(any());
    }

    @ParameterizedTest
    @CsvSource({"PUBLICADO, 503", "AGOTADO, timeout"})
    void falloInicialConservaIntencionYEstadoSinAvisos(EstadoEvento estado, String respuesta) throws Exception {
        Evento evento = evento(estado, false, false);
        when(http.enviar(any(AvisoOutbox.class))).thenAnswer(invocation -> {
            if ("timeout".equals(respuesta)) throw timeout();
            return 503;
        });

        mvc.perform(cancelar(evento.getId(), "Motivo")).andExpect(status().isServiceUnavailable());
        Evento guardado = eventos.findById(evento.getId()).orElseThrow();
        assertEquals(estado, guardado.getEstado());
        assertTrue(guardado.isCancelacionPendiente());
        assertNull(guardado.getCanceladoEn());
        assertEquals(EstadoAvisoOutbox.PENDIENTE, trabajo(evento).getEstado());
        assertEquals(1, trabajo(evento).getIntentos());
        assertEquals(1, outbox.count());
    }

    @Test
    void schedulerRecuperaCorteAntesDeLlamarAReservas() {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        verify(http, never()).enviar(any());
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(204);

        scheduler.procesarPendientes();

        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, trabajo(evento).getEstado());
        assertEquals(3, outbox.count());
        verify(http, times(3)).enviar(any(AvisoOutbox.class));
    }

    @Test
    void respuestaPerdidaRepiteElPutTrasVencerElLeaseSinDuplicar() {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        AvisoOutbox primerReclamo = reclamar(evento);
        AtomicBoolean aplicadoEnReservas = new AtomicBoolean();
        AtomicInteger llamadasReservas = new AtomicInteger();
        when(http.enviar(any(AvisoOutbox.class))).thenAnswer(invocation -> {
            AvisoOutbox aviso = invocation.getArgument(0);
            if ("CANCELACION_RESERVAS".equals(aviso.getTipo())) {
                assertEquals("PUT", aviso.getMetodoHttp());
                assertEquals(Map.of("estado", "CANCELADA"), aviso.getPayload());
                if (llamadasReservas.incrementAndGet() == 1) {
                    aplicadoEnReservas.set(true);
                    throw timeout();
                }
                assertTrue(aplicadoEnReservas.get());
            }
            return 204;
        });
        assertThrows(ResourceAccessException.class, () -> http.enviar(primerReclamo));
        assertEquals(EstadoEvento.PUBLICADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(1, outbox.count());

        ahora.set(primerReclamo.getLeaseHasta().plusSeconds(1));
        scheduler.procesarPendientes();
        assertEquals(2, llamadasReservas.get());
        assertEquals(2, trabajo(evento).getIntentos());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, trabajo(evento).getEstado());
        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(3, outbox.count());
        scheduler.procesarPendientes();
        assertEquals(2, llamadasReservas.get());
        assertEquals(3, outbox.count());
    }

    @Test
    void reclamoVencidoNoConfirmaNiSobrescribeReclamoPosterior() {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        AvisoOutbox anterior = reclamar(evento);
        ahora.set(anterior.getLeaseHasta().plusSeconds(1));
        AvisoOutbox vigente = reclamar(evento);

        assertFalse(cancelaciones.confirmar(anterior, ahora.get()));
        Instant leaseAnterior = anterior.getLeaseHasta();
        anterior.setEstado(EstadoAvisoOutbox.ERROR_FINAL);
        assertFalse(outbox.guardarResultadoReclamo(anterior, leaseAnterior, ahora.get()));
        anterior.setLeaseHasta(vigente.getLeaseHasta());
        assertFalse(cancelaciones.confirmar(anterior, ahora.get()));
        anterior.setIntentos(vigente.getIntentos());
        anterior.setLeaseHasta(leaseAnterior);
        assertFalse(cancelaciones.confirmar(anterior, ahora.get()));
        AvisoOutbox persistido = trabajo(evento);
        assertEquals(EstadoAvisoOutbox.PROCESANDO, persistido.getEstado());
        assertEquals(vigente.getIntentos(), persistido.getIntentos());
        assertEquals(vigente.getLeaseHasta(), persistido.getLeaseHasta());
        assertEquals(EstadoEvento.PUBLICADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(1, outbox.count());

        assertTrue(cancelaciones.confirmar(vigente, ahora.get()));
        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(3, outbox.count());
    }

    @Test
    void leaseExpiradoNoConfirmaAunqueNadieHayaReclamadoDeNuevo() {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        AvisoOutbox anterior = reclamar(evento);
        ahora.set(anterior.getLeaseHasta().plusMillis(1));
        assertFalse(cancelaciones.confirmar(anterior, ahora.get()));
        assertEquals(EstadoAvisoOutbox.PROCESANDO, trabajo(evento).getEstado());
        assertEquals(EstadoEvento.PUBLICADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(1, outbox.count());

        AvisoOutbox vigente = reclamar(evento);
        assertTrue(cancelaciones.confirmar(vigente, ahora.get()));
        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(3, outbox.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202})
    void otro2xxDeReservasNoConfirmaYSeReintenta(int respuesta) throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(respuesta);
        mvc.perform(cancelar(evento.getId(), "Motivo")).andExpect(status().isServiceUnavailable());
        assertEquals(EstadoEvento.PUBLICADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertTrue(eventos.findById(evento.getId()).orElseThrow().isCancelacionPendiente());
        assertEquals(EstadoAvisoOutbox.PENDIENTE, trabajo(evento).getEstado());
        assertEquals(1, outbox.count());
    }

    @Test
    void ochoFallosAgotanElTrabajoSinReintentoAutomatico() {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(503);
        for (int intento = 1; intento <= 8; intento++) {
            assertTrue(dispatcher.procesarAviso(trabajo(evento).getId()));
            AvisoOutbox trabajo = trabajo(evento);
            assertEquals(intento, trabajo.getIntentos());
            assertEquals(intento == 8 ? EstadoAvisoOutbox.ERROR_FINAL : EstadoAvisoOutbox.PENDIENTE,
                    trabajo.getEstado());
            if (intento < 8) ahora.set(trabajo.getProximoIntento().plusMillis(1));
        }
        ahora.set(ahora.get().plusSeconds(3600));
        scheduler.procesarPendientes();
        verify(http, times(8)).enviar(any(AvisoOutbox.class));
        assertEquals(EstadoEvento.PUBLICADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(1, outbox.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409})
    void error4xxDeReservasEsDefinitivo(int respuesta) {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(respuesta);
        assertTrue(dispatcher.procesarAviso(trabajo(evento).getId()));
        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, trabajo(evento).getEstado());
        ahora.set(ahora.get().plusSeconds(3600));
        scheduler.procesarPendientes();
        verify(http).enviar(any(AvisoOutbox.class));
        assertEquals(1, trabajo(evento).getIntentos());
        assertTrue(eventos.findById(evento.getId()).orElseThrow().isCancelacionPendiente());
        assertEquals(1, outbox.count());
    }

    @Test
    void sinTokenEnPerfilNoDockerNoReclamaNiConsumeIntentos() {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        OutboxDispatcher pausado = new OutboxDispatcher(outbox, http, reloj,
                new ServicioToken("", new MockEnvironment()), cancelaciones);
        OutboxScheduler schedulerPausado = new OutboxScheduler(outbox, pausado, reloj);
        assertFalse(pausado.procesarCancelacion(trabajo(evento).getId()));
        schedulerPausado.procesarPendientes();
        assertEquals(EstadoAvisoOutbox.PENDIENTE, trabajo(evento).getEstado());
        assertEquals(0, trabajo(evento).getIntentos());
        assertNull(trabajo(evento).getLeaseHasta());
        verify(http, never()).enviar(any());
    }

    @ParameterizedTest
    @CsvSource({"204, ENTREGADO", "500, PENDIENTE", "timeout, PENDIENTE", "400, ERROR_FINAL"})
    void avisoFilaSeEntregaReintentaOQuedaEnErrorFinal(String respuesta, EstadoAvisoOutbox esperado)
            throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        AtomicInteger entregasFila = new AtomicInteger();
        when(http.enviar(any(AvisoOutbox.class))).thenAnswer(invocation -> {
            AvisoOutbox aviso = invocation.getArgument(0);
            if (!"CANCELACION_FILA".equals(aviso.getTipo())) return 204;
            if (entregasFila.incrementAndGet() > 1) return 204;
            if ("timeout".equals(respuesta)) throw timeout();
            return Integer.parseInt(respuesta);
        });
        mvc.perform(cancelar(evento.getId(), "Motivo")).andExpect(status().isOk());
        AvisoOutbox fila = aviso("cancelacion-fila-" + evento.getId());
        assertEquals(esperado, fila.getEstado());
        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso("cancelacion-" + evento.getId()).getEstado());
        assertEquals(1, fila.getIntentos());

        if (esperado == EstadoAvisoOutbox.PENDIENTE) {
            ahora.set(fila.getProximoIntento().plusMillis(1));
            scheduler.procesarPendientes();
            assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso(fila.getId()).getEstado());
            assertEquals(2, entregasFila.get());
        } else {
            ahora.set(ahora.get().plusSeconds(3600));
            scheduler.procesarPendientes();
            assertEquals(1, entregasFila.get());
        }
    }

    @Test
    void avisoFilaPersistidoEnviaPutSinBodyYHeadersComunes() throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        when(http.enviar(any(AvisoOutbox.class))).thenReturn(204);
        mvc.perform(cancelar(evento.getId(), "Motivo")).andExpect(status().isOk());
        AvisoOutbox fila = aviso("cancelacion-fila-" + evento.getId());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOutboxHttpClient cliente = clienteHttp(builder);
        server.expect(requestTo(fila.getDestino()))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Aviso-Id", fila.getId()))
                .andExpect(header("X-Service-Token", "token-prueba"))
                .andExpect(header("X-Correlation-Id", "traza-cancelacion"))
                .andExpect(headerDoesNotExist("Content-Type"))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertEquals(204, cliente.enviar(fila));
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"204, ENTREGADO", "500, PENDIENTE", "timeout, PENDIENTE", "400, ERROR_FINAL"})
    void dispatcherEnviaFilaPorHttpYAplicaLaPoliticaReal(String respuesta, EstadoAvisoOutbox esperado) {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        registrar(evento, "Motivo");
        assertTrue(cancelaciones.confirmar(reclamar(evento), ahora.get()));
        AvisoOutbox fila = aviso("cancelacion-fila-" + evento.getId());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var primera = server.expect(requestTo(fila.getDestino()))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Aviso-Id", fila.getId()))
                .andExpect(header("X-Service-Token", "token-prueba"))
                .andExpect(header("X-Correlation-Id", "traza-cancelacion"))
                .andExpect(headerDoesNotExist("Content-Type"))
                .andExpect(content().bytes(new byte[0]));
        if ("timeout".equals(respuesta)) {
            primera.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                    .withException(new SocketTimeoutException("timeout")));
        } else {
            primera.andRespond(withStatus(HttpStatus.valueOf(Integer.parseInt(respuesta))));
        }
        if (esperado == EstadoAvisoOutbox.PENDIENTE) {
            server.expect(requestTo(fila.getDestino()))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(header("X-Aviso-Id", fila.getId()))
                    .andExpect(header("X-Service-Token", "token-prueba"))
                    .andExpect(header("X-Correlation-Id", "traza-cancelacion"))
                    .andExpect(headerDoesNotExist("Content-Type"))
                    .andExpect(content().bytes(new byte[0]))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));
        }
        OutboxDispatcher dispatcherHttp = new OutboxDispatcher(outbox, clienteHttp(builder), reloj,
                new ServicioToken("token-prueba", new MockEnvironment()), cancelaciones);

        assertTrue(dispatcherHttp.procesarAviso(fila.getId()));
        fila = aviso(fila.getId());
        assertEquals(esperado, fila.getEstado());
        if (esperado == EstadoAvisoOutbox.PENDIENTE) {
            ahora.set(fila.getProximoIntento().plusMillis(1));
            assertTrue(dispatcherHttp.procesarAviso(fila.getId()));
            assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso(fila.getId()).getEstado());
            assertEquals(2, aviso(fila.getId()).getIntentos());
        } else {
            ahora.set(ahora.get().plusSeconds(3600));
            assertFalse(dispatcherHttp.procesarAviso(fila.getId()));
            assertEquals(1, aviso(fila.getId()).getIntentos());
        }
        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso("cancelacion-" + evento.getId()).getEstado());
        assertEquals(EstadoEvento.CANCELADO, eventos.findById(evento.getId()).orElseThrow().getEstado());
        server.verify();
    }

    @Test
    void avisoLegacyPersistidoSinMetodoConservaPostPayloadYHeaders() {
        AvisoOutbox legacy = new AvisoOutbox();
        legacy.setId("legacy-1");
        legacy.setTipo("CANCELACION");
        legacy.setDestino("http://compras.test/eventos-cancelados");
        legacy.setCorrelationId("traza-legacy");
        legacy.setPayload(Map.of("eventoId", "evento-1", "motivo", "Motivo"));
        outbox.insert(legacy);
        assertFalse(mongo.getCollection("outbox").find(new org.bson.Document("_id", "legacy-1"))
                .first().containsKey("metodoHttp"));
        AvisoOutbox persistido = aviso("legacy-1");
        assertNull(persistido.getMetodoHttp());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(legacy.getDestino()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Aviso-Id", "legacy-1"))
                .andExpect(header("X-Service-Token", "token-prueba"))
                .andExpect(header("X-Correlation-Id", "traza-legacy"))
                .andExpect(content().json("{\"eventoId\":\"evento-1\",\"motivo\":\"Motivo\"}"))
                .andRespond(withStatus(HttpStatus.OK));
        assertEquals(200, clienteHttp(builder).enviar(persistido));
        server.verify();
    }

    @Test
    void intencionPendienteImpideAdministracionYFinalizacion() throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, true);
        registrar(evento, "Motivo");
        for (String operacion : List.of("suspender", "reactivar")) {
            mvc.perform(administrarVenta(evento.getId(), operacion)).andExpect(status().isConflict());
        }
        lifecycle.finalizarVencidos();
        verify(reservas, never()).cambiarEstadoVenta(any(), any());
        Evento persistido = eventos.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO, persistido.getEstado());
        assertTrue(persistido.isCancelacionPendiente());
        assertFalse(persistido.isVentaSuspendida());
    }

    @Test
    void conflictoDeReservasAlSuspenderOReactivarResponde409SinCambios() throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventoLifecycleService servicio = new EventoLifecycleService(eventoService,
                new HttpReservasClient(builder.build()), cancelaciones, publicaciones, dispatcher,
                new com.ticketya.gestionlugares.reservas.ReservasSincrono(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()));
        MockMvc mvcConReservasHttp = MockMvcBuilders
                .standaloneSetup(new EventoController(eventoService, servicio))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String estado : List.of("SUSPENDIDA", "ACTIVA")) {
            server.expect(requestTo("http://reservas.test/disponibilidad/eventos/" + evento.getId() + "/venta"))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(content().json("{\"estado\":\"" + estado + "\"}"))
                    .andRespond(withStatus(HttpStatus.CONFLICT));
        }
        for (String operacion : List.of("suspender", "reactivar")) {
            mvcConReservasHttp.perform(administrarVenta(evento.getId(), operacion)).andExpect(status().isConflict());
        }
        Evento persistido = eventos.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO, persistido.getEstado());
        assertFalse(persistido.isVentaSuspendida());
        assertEquals(0, outbox.count());
        server.verify();
    }

    @Test
    void cacheDeBusquedaSoloSeInvalidaDespuesDelCommit() throws Exception {
        Evento evento = evento(EstadoEvento.PUBLICADO, false, false);
        mvc.perform(get("/eventos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));
        assertEquals(1, cache().getNativeCache().estimatedSize());
        registrar(evento, "Motivo");
        AvisoOutbox reclamado = reclamar(evento);

        transaccion.executeWithoutResult(status -> {
            assertTrue(cancelaciones.confirmar(reclamado, ahora.get()));
            assertEquals(1, cache().getNativeCache().estimatedSize());
        });
        assertEquals(0, cache().getNativeCache().estimatedSize());
        mvc.perform(get("/eventos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    private Evento evento(EstadoEvento estado, boolean requiereFila, boolean vencido) {
        Evento evento = Evento.borrador("org", "lugar", "CABA", "Show", "Descripcion", "MUSICA",
                List.of("Artista"), vencido ? Instant.parse("2020-01-01T00:00:00Z")
                        : Instant.parse("2035-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("sector", "Platea", TipoSector.NUMERADO, 1, 1, null, null)));
        evento.setEstado(estado);
        evento.setRequiereFila(requiereFila);
        return eventos.insert(evento);
    }

    private void registrar(Evento evento, String motivo) {
        try (Correlacion.Contexto contexto = Correlacion.usar("traza-cancelacion")) {
            cancelaciones.registrarIntencion(evento.getId(), motivo, ahora.get().minusSeconds(1));
        }
    }

    private AvisoOutbox reclamar(Evento evento) {
        return outbox.reclamarPorId("cancelacion-reservas-" + evento.getId(),
                ahora.get(), ahora.get().plusSeconds(60), OutboxDispatcher.MAX_INTENTOS).orElseThrow();
    }

    private AvisoOutbox trabajo(Evento evento) {
        return aviso("cancelacion-reservas-" + evento.getId());
    }

    private AvisoOutbox aviso(String id) {
        return outbox.findById(id).orElseThrow();
    }

    private CaffeineCache cache() {
        return (CaffeineCache) cacheManager.getCache(CatalogoCacheConfiguration.BUSQUEDA_EVENTOS);
    }

    private static ResourceAccessException timeout() {
        return new ResourceAccessException("timeout", new SocketTimeoutException("timeout"));
    }

    private static RestClientOutboxHttpClient clienteHttp(RestClient.Builder builder) {
        return new RestClientOutboxHttpClient(builder.build(), builder.build(),
                new ServicioToken("token-prueba", new MockEnvironment()));
    }

    private static MockHttpServletRequestBuilder cancelar(String id, String motivo) {
        return put("/eventos/{id}/cancelar", id).header("X-User-Id", "admin")
                .header("X-User-Roles", "ADMIN").header("X-Correlation-Id", "traza-cancelacion")
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"" + motivo + "\"}");
    }

    private static MockHttpServletRequestBuilder administrarVenta(String id, String operacion) {
        return put("/eventos/{id}/venta/{operacion}", id, operacion)
                .header("X-User-Id", "admin").header("X-User-Roles", "ADMIN");
    }
}
