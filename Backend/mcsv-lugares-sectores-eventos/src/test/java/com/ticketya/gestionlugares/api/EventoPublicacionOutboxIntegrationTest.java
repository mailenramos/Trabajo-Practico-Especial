package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;

@SpringBootTest(properties = {"outbox.dispatcher.interval=3600000", "servicio.token=token-prueba"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventoPublicacionOutboxIntegrationTest {

    private static final String PREFERENCIAS_PATH = "/internal/preferencias/eventos-publicados";
    private static final Instant FECHA_EVENTO = Instant.parse("2030-01-02T03:04:05Z");
    private static final ConcurrentLinkedQueue<Integer> RESPUESTAS = new ConcurrentLinkedQueue<>();
    private static final List<RequestCapture> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<EventoRepository> EVENTO_REPOSITORY = new AtomicReference<>();
    private static final HttpServer PREFERENCIAS = iniciarServidorPreferencias();

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
        registry.add("preferencias.base-url", () -> "http://127.0.0.1:" + PREFERENCIAS.getAddress().getPort());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EventoRepository eventoRepository;
    @Autowired private OutboxAvisoRepository outboxRepository;
    @Autowired private OutboxDispatcher outboxDispatcher;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private ReservasClient reservasClient;

    @BeforeEach
    void limpiarDatos() {
        outboxRepository.deleteAll();
        eventoRepository.deleteAll();
        REQUESTS.clear();
        RESPUESTAS.clear();
        EVENTO_REPOSITORY.set(eventoRepository);
        reset(reservasClient);
    }

    @AfterAll
    static void detenerServidorPreferencias() {
        PREFERENCIAS.stop(0);
    }

    @Test
    void publicaConAvisoOutboxYEntregaConReintentoYErrorDefinitivo() throws Exception {
        RESPUESTAS.add(503);
        RESPUESTAS.add(202);
        RESPUESTAS.add(400);

        Evento primerEvento = guardarBorrador("evento-publicacion-reintento", null);
        mockMvc.perform(solicitudPublicacion(primerEvento.getId()))
                .andExpect(status().isOk());

        String primerId = "publicacion-" + primerEvento.getId();
        AvisoOutbox pendiente = outboxRepository.findById(primerId).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO,
                eventoRepository.findById(primerEvento.getId()).orElseThrow().getEstado());
        assertEquals("PUBLICACION", pendiente.getTipo());
        assertEquals(destinoPreferencias(), pendiente.getDestino());
        assertEquals(payloadEsperado(primerEvento, List.of()), pendiente.getPayload());
        assertEquals(EstadoAvisoOutbox.PENDIENTE, pendiente.getEstado());
        assertEquals(1, pendiente.getIntentos());
        pendiente.setProximoIntento(Instant.now().minusSeconds(1));
        outboxRepository.save(pendiente);

        assertTrue(outboxDispatcher.procesarAviso(primerId));
        AvisoOutbox entregado = outboxRepository.findById(primerId).orElseThrow();
        assertEquals(EstadoAvisoOutbox.ENTREGADO, entregado.getEstado());
        assertEquals(2, entregado.getIntentos());

        Evento segundoEvento = guardarBorrador("evento-publicacion-definitiva", List.of("Artista"));
        mockMvc.perform(solicitudPublicacion(segundoEvento.getId()))
                .andExpect(status().isOk());

        AvisoOutbox errorFinal = outboxRepository.findById(
                "publicacion-" + segundoEvento.getId()).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO,
                eventoRepository.findById(segundoEvento.getId()).orElseThrow().getEstado());
        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, errorFinal.getEstado());
        assertEquals(1, errorFinal.getIntentos());
        assertEquals(destinoPreferencias(), errorFinal.getDestino());
        assertEquals(payloadEsperado(segundoEvento, List.of("Artista")), errorFinal.getPayload());

        assertEquals(3, REQUESTS.size());
        assertPublicacionRequest(REQUESTS.get(0), primerEvento.getId(), List.of());
        assertPublicacionRequest(REQUESTS.get(1), primerEvento.getId(), List.of());
        assertPublicacionRequest(REQUESTS.get(2), segundoEvento.getId(), List.of("Artista"));
        assertEquals(REQUESTS.get(0).body(), REQUESTS.get(1).body());
        verify(reservasClient, org.mockito.Mockito.times(2))
                .cambiarEstadoVenta(any(), org.mockito.ArgumentMatchers.eq(EstadoVentaReservas.ACTIVA));
    }

    @Test
    void revierteEventoSiNoPuedeInsertarElAvisoOutbox() throws Exception {
        Evento evento = guardarBorrador("evento-publicacion-rollback", List.of("Artista"));
        AvisoOutbox duplicado = avisoExistente("publicacion-" + evento.getId());
        outboxRepository.insert(duplicado);

        mockMvc.perform(solicitudPublicacion(evento.getId()))
                .andExpect(status().isConflict());

        assertEquals(EstadoEvento.BORRADOR,
                eventoRepository.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(1, outboxRepository.count());
        assertTrue(REQUESTS.isEmpty());
    }

    @Test
    void falloDeReservasDejaElEventoEnBorradorYSinAviso() throws Exception {
        Evento evento = guardarBorrador("evento-publicacion-reservas-falla", List.of("Artista"));
        doThrow(new ReservasClientException("HTTP 503"))
                .when(reservasClient).inicializarDisponibilidad(any(), any());

        mockMvc.perform(solicitudPublicacion(evento.getId()))
                .andExpect(status().isServiceUnavailable());

        assertEquals(EstadoEvento.BORRADOR,
                eventoRepository.findById(evento.getId()).orElseThrow().getEstado());
        assertEquals(0, outboxRepository.count());
        assertTrue(REQUESTS.isEmpty());
        verify(reservasClient, never()).cambiarEstadoVenta(any(), any());
    }

    private void assertPublicacionRequest(RequestCapture request, String eventoId,
            List<String> participantesEsperados) throws Exception {
        assertEquals("POST", request.method());
        assertEquals(PREFERENCIAS_PATH, request.path());
        assertEquals("publicacion-" + eventoId, request.avisoId());
        assertEquals("token-prueba", request.serviceToken());
        assertEquals(EstadoEvento.PUBLICADO.name(), request.estadoEvento());
        JsonNode payload = objectMapper.readTree(request.body());
        assertEquals(eventoId, payload.get("eventoId").asText());
        assertEquals("Evento " + eventoId, payload.get("nombre").asText());
        assertEquals("MUSICA", payload.get("tipo").asText());
        assertTrue(payload.get("participantes").isArray());
        assertEquals(participantesEsperados.size(), payload.get("participantes").size());
        for (int index = 0; index < participantesEsperados.size(); index++) {
            assertEquals(participantesEsperados.get(index), payload.get("participantes").get(index).asText());
        }
        assertEquals("CABA", payload.get("ciudad").asText());
        assertEquals(FECHA_EVENTO.toString(), payload.get("fechaHora").asText());
    }

    private Evento guardarBorrador(String id, List<String> participantes) {
        Evento evento = Evento.borrador("organizador-1", "lugar-1", "CABA", "Evento " + id,
                "Descripción", "MUSICA", List.of(), FECHA_EVENTO, 4,
                List.of(new EventoSector("sector-1", "Platea", TipoSector.NO_NUMERADO,
                        null, null, 100, new BigDecimal("250"))));
        evento.setId(id);
        evento.setParticipantes(participantes);
        return eventoRepository.save(evento);
    }

    private Map<String, Object> payloadEsperado(Evento evento, List<String> participantes) {
        return Map.of(
                "eventoId", evento.getId(),
                "nombre", evento.getNombre(),
                "tipo", evento.getTipo(),
                "participantes", participantes,
                "ciudad", evento.getCiudad(),
                "fechaHora", FECHA_EVENTO.toString());
    }

    private static String destinoPreferencias() {
        return "http://127.0.0.1:" + PREFERENCIAS.getAddress().getPort() + PREFERENCIAS_PATH;
    }

    private AvisoOutbox avisoExistente(String id) {
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId(id);
        aviso.setTipo("PUBLICACION");
        aviso.setEventoId(id.substring("publicacion-".length()));
        aviso.setDestino(destinoPreferencias());
        aviso.setPayload(Map.of("eventoId", aviso.getEventoId()));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(Instant.now());
        aviso.setCreadoEn(Instant.now());
        aviso.setActualizadoEn(Instant.now());
        return aviso;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder solicitudPublicacion(
            String eventoId) {
        return put("/eventos/{eventoId}/publicar", eventoId)
                .header("X-User-Id", "organizador-1")
                .header("X-User-Roles", "ORGANIZADOR");
    }

    private static HttpServer iniciarServidorPreferencias() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(PREFERENCIAS_PATH, exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String eventoId = new ObjectMapper().readTree(body).get("eventoId").asText();
                EstadoEvento estadoEvento = EVENTO_REPOSITORY.get().findById(eventoId)
                        .map(Evento::getEstado).orElse(null);
                REQUESTS.add(new RequestCapture(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Aviso-Id"),
                        exchange.getRequestHeaders().getFirst("X-Service-Token"), body, estadoEvento == null
                                ? null : estadoEvento.name()));
                Integer status = RESPUESTAS.poll();
                exchange.sendResponseHeaders(status == null ? 500 : status, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record RequestCapture(String method, String path, String avisoId, String serviceToken,
            String body, String estadoEvento) {
    }
}
