package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxService;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.outbox.OutboxHttpClient;
import com.ticketya.gestionlugares.outbox.OutboxScheduler;
import com.ticketya.gestionlugares.outbox.RestClientOutboxHttpClient;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"outbox.dispatcher.interval=3600000",
        "eventos.finalizacion.interval=3600000", "servicio.token=token-prueba",
        "compras.base-url=http://compras.test"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ModificacionOutboxIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");
    private static final String FECHA = "2035-02-01T12:00:00Z";
    private static final String CORRELACION = "modificacion-traza";

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_modificacion_outbox"));
    }

    @Autowired private MockMvc mvc;
    @Autowired private EventoRepository eventos;
    @Autowired private LugarRepository lugares;
    @Autowired private OutboxAvisoRepository outbox;
    @Autowired private EventoCancelacionOutboxService cancelaciones;
    @Autowired private ObjectMapper mapper;
    @MockBean private OutboxHttpClient httpProgramado;
    @MockBean(name = "outboxClock") private Clock reloj;

    private final AtomicReference<Instant> ahora = new AtomicReference<>();
    private MockRestServiceServer servidor;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void preparar() {
        ahora.set(Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS));
        when(reloj.instant()).thenAnswer(invocation -> ahora.get());
        outbox.deleteAll();
        eventos.deleteAll();
        lugares.deleteAll();
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        ServicioToken token = new ServicioToken("token-prueba", new MockEnvironment());
        RestClientOutboxHttpClient cliente = new RestClientOutboxHttpClient(builder.build(), builder.build(), token);
        dispatcher = new OutboxDispatcher(outbox, cliente, reloj, token, cancelaciones);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202})
    void respuestaExitosaEntregaAvisoConContratoCompleto(int respuesta) throws Exception {
        Evento evento = evento();
        AvisoOutbox aviso = modificar(evento, FECHA);
        esperarSolicitud(aviso, null).andRespond(withStatus(HttpStatus.valueOf(respuesta)));

        assertTrue(dispatcher.procesarAviso(aviso.getId()));
        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso(aviso).getEstado());
        assertEquals(1, aviso(aviso).getIntentos());
        servidor.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403})
    void respuestaDefinitivaNoSeReintenta(int respuesta) throws Exception {
        AvisoOutbox aviso = modificar(evento(), FECHA);
        esperarSolicitud(aviso, null).andRespond(withStatus(HttpStatus.valueOf(respuesta)));

        assertTrue(dispatcher.procesarAviso(aviso.getId()));
        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, aviso(aviso).getEstado());
        ahora.set(ahora.get().plusSeconds(3600));
        assertFalse(dispatcher.procesarAviso(aviso.getId()));
        assertEquals(1, aviso(aviso).getIntentos());
        servidor.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"500", "timeout", "conexion"})
    void falloTemporalReintentaConMismoBodyYVersion(String fallo) throws Exception {
        AvisoOutbox aviso = modificar(evento(), FECHA);
        AtomicReference<String> body = new AtomicReference<>();
        var primera = esperarSolicitud(aviso, body);
        switch (fallo) {
            case "500" -> primera.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            case "timeout" -> primera.andRespond(withException(new SocketTimeoutException("timeout")));
            case "conexion" -> primera.andRespond(withException(new ConnectException("conexion rechazada")));
            default -> throw new IllegalArgumentException(fallo);
        }
        esperarSolicitud(aviso, body).andRespond(withStatus(HttpStatus.ACCEPTED));

        Instant inicio = ahora.get();
        assertTrue(dispatcher.procesarAviso(aviso.getId()));
        AvisoOutbox pendiente = aviso(aviso);
        assertEquals(EstadoAvisoOutbox.PENDIENTE, pendiente.getEstado());
        assertEquals(inicio.plusSeconds(30), pendiente.getProximoIntento());
        assertFalse(dispatcher.procesarAviso(aviso.getId()));
        ahora.set(pendiente.getProximoIntento());
        assertTrue(dispatcher.procesarAviso(aviso.getId()));
        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso(aviso).getEstado());
        assertEquals(2, aviso(aviso).getIntentos());
        servidor.verify();
    }

    @Test
    void backoffDuplicadoTieneTopeYAgotaOchoReclamos() throws Exception {
        AvisoOutbox aviso = modificar(evento(), FECHA);
        for (int i = 0; i < 8; i++) {
            esperarSolicitud(aviso, null).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }
        long[] esperas = {30, 60, 120, 240, 480, 900, 900};
        for (int i = 0; i < 8; i++) {
            Instant inicio = ahora.get();
            assertTrue(dispatcher.procesarAviso(aviso.getId()));
            AvisoOutbox actual = aviso(aviso);
            assertEquals(i + 1, actual.getIntentos());
            if (i < esperas.length) {
                assertEquals(EstadoAvisoOutbox.PENDIENTE, actual.getEstado());
                assertEquals(inicio.plusSeconds(esperas[i]), actual.getProximoIntento());
                assertFalse(dispatcher.procesarAviso(aviso.getId()));
                ahora.set(actual.getProximoIntento());
            } else {
                assertEquals(EstadoAvisoOutbox.ERROR_FINAL, actual.getEstado());
                ahora.set(ahora.get().plusSeconds(3600));
                assertFalse(dispatcher.procesarAviso(aviso.getId()));
            }
        }
        servidor.verify();
    }

    @Test
    void sinTokenDispatcherYSchedulerNoConsumenIntentos() throws Exception {
        AvisoOutbox aviso = modificar(evento(), FECHA);
        OutboxDispatcher pausado = new OutboxDispatcher(outbox, httpProgramado, reloj,
                new ServicioToken("", new MockEnvironment()), cancelaciones);
        assertFalse(pausado.procesarAviso(aviso.getId()));
        new OutboxScheduler(outbox, pausado, reloj).procesarPendientes();
        AvisoOutbox pendiente = aviso(aviso);
        assertEquals(EstadoAvisoOutbox.PENDIENTE, pendiente.getEstado());
        assertEquals(0, pendiente.getIntentos());
        assertNull(pendiente.getLeaseHasta());
        assertNull(pendiente.getUltimoError());
        org.mockito.Mockito.verifyNoInteractions(httpProgramado);
    }

    @Test
    void segundaModificacionNoPisaAvisoAnteriorPendiente() throws Exception {
        Evento evento = evento();
        AvisoOutbox primero = modificar(evento, FECHA);
        AvisoOutbox segundo = modificar(evento, "2035-03-01T12:00:00Z");
        assertTrue(((Number) segundo.getPayload().get("version")).longValue()
                > ((Number) primero.getPayload().get("version")).longValue());
        assertEquals(2, outbox.count());
        esperarSolicitud(segundo, null).andRespond(withStatus(HttpStatus.ACCEPTED));
        assertTrue(dispatcher.procesarAviso(segundo.getId()));
        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso(primero).getEstado());
        assertEquals(FECHA, aviso(primero).getPayload().get("fechaHora"));
        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso(segundo).getEstado());
        servidor.verify();
    }

    @Test
    void falloDeComprasNoRevierteNiHaceFallarPut() throws Exception {
        Evento evento = evento();
        AtomicInteger llamadas = new AtomicInteger();
        servidor.expect(requestTo("http://compras.test/internal/eventos-modificados"))
                .andExpect(request -> llamadas.incrementAndGet())
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        AvisoOutbox aviso = modificar(evento, FECHA);
        assertEquals(0, llamadas.get());
        assertTrue(dispatcher.procesarAviso(aviso.getId()));
        assertEquals(1, llamadas.get());
        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso(aviso).getEstado());
        assertEquals(Instant.parse(FECHA), eventos.findById(evento.getId()).orElseThrow().getFechaHora());
        servidor.verify();
    }

    private Evento evento() {
        Lugar lugar = lugares.insert(new Lugar(null, "Teatro", "Calle", "Rosario", 0, 0, List.of()));
        Evento evento = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), "Show", "Descripcion",
                "MUSICA", List.of("Artista"), Instant.parse("2035-01-01T12:00:00Z"), 4, List.of());
        evento.setEstado(EstadoEvento.PUBLICADO);
        return eventos.insert(evento);
    }

    private AvisoOutbox modificar(Evento evento, String fecha) throws Exception {
        mvc.perform(put("/eventos/{id}", evento.getId())
                        .header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR")
                        .header("X-Correlation-Id", CORRELACION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaHora\":\"" + fecha + "\"}"))
                .andExpect(status().isOk());
        Evento guardado = eventos.findById(evento.getId()).orElseThrow();
        return outbox.findById("modificacion-" + guardado.getId() + "-v" + guardado.getVersion()).orElseThrow();
    }

    private AvisoOutbox aviso(AvisoOutbox aviso) {
        return outbox.findById(aviso.getId()).orElseThrow();
    }

    private org.springframework.test.web.client.ResponseActions esperarSolicitud(
            AvisoOutbox aviso, AtomicReference<String> bodyAnterior) {
        return servidor.expect(requestTo(aviso.getDestino()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", "token-prueba"))
                .andExpect(header("X-Aviso-Id", aviso.getId()))
                .andExpect(header("X-Correlation-Id", CORRELACION))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    assertEquals("/internal/eventos-modificados", request.getURI().getPath());
                    String body = new String(((MockClientHttpRequest) request).getBodyAsBytes(), StandardCharsets.UTF_8);
                    JsonNode json = mapper.readTree(body);
                    assertEquals(Set.of("eventoId", "version", "fechaHora", "lugar", "ciudad"),
                            mapper.convertValue(json, java.util.Map.class).keySet());
                    assertEquals(aviso.getEventoId(), json.path("eventoId").asText());
                    assertTrue(json.path("version").isIntegralNumber());
                    assertEquals(((Number) aviso.getPayload().get("version")).longValue(),
                            json.path("version").asLong());
                    assertEquals(aviso.getPayload().get("fechaHora"), json.path("fechaHora").asText());
                    assertTrue(json.path("fechaHora").asText().endsWith("Z"));
                    assertEquals("Teatro", json.path("lugar").asText());
                    assertEquals("Rosario", json.path("ciudad").asText());
                    if (bodyAnterior != null) {
                        if (bodyAnterior.get() == null) bodyAnterior.set(body);
                        else assertEquals(bodyAnterior.get(), body);
                    }
                });
    }
}
