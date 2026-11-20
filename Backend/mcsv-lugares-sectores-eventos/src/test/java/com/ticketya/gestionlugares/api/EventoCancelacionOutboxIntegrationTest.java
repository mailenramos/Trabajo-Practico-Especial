package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxBackfill;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.outbox.OutboxHttpClient;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;

@SpringBootTest(properties = {
        "outbox.dispatcher.interval=3600000",
        "servicio.token=token-prueba"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventoCancelacionOutboxIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EventoRepository eventoRepository;
    @Autowired private OutboxAvisoRepository outboxRepository;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private EventoCancelacionOutboxBackfill backfill;
    @Autowired private OutboxDispatcher outboxDispatcher;
    @MockBean private ReservasClient reservasClient;
    @MockBean private OutboxHttpClient outboxHttpClient;

    @BeforeEach
    void limpiarDatos() {
        outboxRepository.deleteAll();
        eventoRepository.deleteAll();
        reset(reservasClient, outboxHttpClient);
    }

    @Test
    void cancelaEnUnaTransaccionYEntregaAvisoOutbox() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return 204;
        });

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo")
                        .header("X-Correlation-Id", "cancelacion-traza"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cancelado"))
                .andExpect(jsonPath("$.avisoCancelacionPendiente").doesNotExist());

        Evento persistido = eventoRepository.findById(evento.getId()).orElseThrow();
        AvisoOutbox aviso = outboxRepository.findById("cancelacion-" + evento.getId()).orElseThrow();
        assertEquals("cancelacion-traza", aviso.getCorrelationId());
        assertEquals(EstadoEvento.CANCELADO, persistido.getEstado());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso.getEstado());
        assertEquals(evento.getId(), aviso.getEventoId());
        assertTrue(aviso.getDestino().endsWith("/eventos-cancelados"));
        assertEquals(evento.getId(), aviso.getPayload().get("eventoId"));
        assertEquals("Cierre preventivo", aviso.getPayload().get("motivo"));
        assertNotNull(aviso.getPayload().get("canceladoEn"));
        verify(outboxHttpClient, org.mockito.Mockito.times(3)).enviar(any(AvisoOutbox.class));
    }

    @Test
    void falloAlInsertarAvisoRevierteElCambioDelEvento() throws Exception {
        Evento evento = guardarEventoPublicado();
        outboxRepository.insert(avisoExistente("cancelacion-" + evento.getId()));
        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenReturn(204);

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isServiceUnavailable());

        Evento persistido = eventoRepository.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO, persistido.getEstado());
        assertEquals(2, outboxRepository.count());
        verify(outboxHttpClient).enviar(any(AvisoOutbox.class));
    }

    @Test
    void falloDeComprasMantieneRespuesta200YAvisoPendiente() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenAnswer(invocation -> {
            AvisoOutbox aviso = invocation.getArgument(0);
            if ("CANCELACION".equals(aviso.getTipo())) {
                throw new ResourceAccessException("Compras no responde", new ConnectException("connection refused"));
            }
            return 204;
        });

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cancelado"))
                .andExpect(jsonPath("$.avisoCancelacionPendiente").doesNotExist());

        Evento persistido = eventoRepository.findById(evento.getId()).orElseThrow();
        AvisoOutbox aviso = outboxRepository.findById("cancelacion-" + evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.CANCELADO, persistido.getEstado());
        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso.getEstado());
        assertEquals(1, aviso.getIntentos());
        assertEquals("Cierre preventivo", aviso.getPayload().get("motivo"));
    }

    @Test
    void cancelarDosVecesDevuelve409YSoloDejaUnAviso() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenReturn(204);

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isOk());
        mockMvc.perform(solicitudCancelacion(evento.getId(), "Otro motivo"))
                .andExpect(status().isConflict());

        assertEquals(3, outboxRepository.count());
        verify(outboxHttpClient, org.mockito.Mockito.times(3)).enviar(any(AvisoOutbox.class));
    }

    @Test
    void backfillMigraAvisosValidosEsIdempotenteYOmitidosNoBloqueanLaEntrega() {
        Instant canceladoEn = Instant.parse("2026-11-18T18:00:00Z");
        Evento migrable = guardarEventoLegacy(
                "legacy-migrable", EstadoEvento.CANCELADO, "Motivo existente", canceladoEn);
        Evento yaMigrado = guardarEventoLegacy(
                "legacy-ya-migrado", EstadoEvento.CANCELADO, "Otro motivo", canceladoEn);
        Evento noCancelado = guardarEventoLegacy(
                "legacy-no-cancelado", EstadoEvento.PUBLICADO, "Motivo existente", canceladoEn);
        Evento sinMotivo = guardarEventoLegacy(
                "legacy-sin-motivo", EstadoEvento.CANCELADO, null, canceladoEn);
        Evento sinFecha = guardarEventoLegacy(
                "legacy-sin-fecha", EstadoEvento.CANCELADO, "Motivo existente", null);

        AvisoOutbox existente = avisoExistente("cancelacion-" + yaMigrado.getId());
        existente.setEstado(EstadoAvisoOutbox.ENTREGADO);
        existente.setIntentos(4);
        existente.setPayload(Map.of("original", "no sobrescribir"));
        outboxRepository.insert(existente);

        Instant inicio = Instant.now();
        EventoCancelacionOutboxBackfill.Resultado primero = backfill.migrarPendientes();
        Instant fin = Instant.now();

        assertEquals(new EventoCancelacionOutboxBackfill.Resultado(1, 1, 3), primero);
        AvisoOutbox creado = outboxRepository.findById("cancelacion-" + migrable.getId()).orElseThrow();
        assertEquals(EstadoAvisoOutbox.PENDIENTE, creado.getEstado());
        assertEquals(0, creado.getIntentos());
        assertFalse(creado.getProximoIntento().isBefore(inicio.minusSeconds(1)));
        assertFalse(creado.getProximoIntento().isAfter(fin.plusSeconds(1)));
        assertEquals(migrable.getId(), creado.getPayload().get("eventoId"));
        assertEquals("Motivo existente", creado.getPayload().get("motivo"));
        assertEquals(canceladoEn.toString(), creado.getPayload().get("canceladoEn"));

        AvisoOutbox sinCambios = outboxRepository.findById(existente.getId()).orElseThrow();
        assertEquals(EstadoAvisoOutbox.ENTREGADO, sinCambios.getEstado());
        assertEquals(4, sinCambios.getIntentos());
        assertEquals(Map.of("original", "no sobrescribir"), sinCambios.getPayload());
        assertFalse(tieneFlagLegacy(migrable.getId()));
        assertFalse(tieneFlagLegacy(yaMigrado.getId()));

        EventoCancelacionOutboxBackfill.Resultado segundo = backfill.migrarPendientes();
        assertEquals(new EventoCancelacionOutboxBackfill.Resultado(0, 0, 3), segundo);
        assertEquals(2, outboxRepository.count());
        assertEquals(Set.of(noCancelado.getId(), sinMotivo.getId(), sinFecha.getId()),
                mongoTemplate.find(Query.query(Criteria.where("avisoCancelacionPendiente").is(true)),
                                org.bson.Document.class, "eventos").stream()
                        .map(documento -> documento.get("_id").toString()).collect(Collectors.toSet()));

        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenReturn(202);
        assertTrue(outboxDispatcher.procesarAviso(creado.getId()));
        assertEquals(EstadoAvisoOutbox.ENTREGADO,
                outboxRepository.findById(creado.getId()).orElseThrow().getEstado());
    }

    private Evento guardarEventoPublicado() {
        Evento evento = Evento.borrador("organizador-1", "lugar-1", "CABA", "Show", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("sector-1", "Platea", TipoSector.NUMERADO, 1, 1, null, null)));
        evento.setEstado(EstadoEvento.PUBLICADO);
        return eventoRepository.save(evento);
    }

    private Evento guardarEventoLegacy(String id, EstadoEvento estado, String motivo, Instant canceladoEn) {
        Evento evento = Evento.borrador("organizador-1", "lugar-1", "CABA", "Show", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        evento.setId(id);
        evento.setEstado(estado);
        evento.setMotivoCancelacion(motivo);
        evento.setCanceladoEn(canceladoEn);
        Evento guardado = eventoRepository.save(evento);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(guardado.getId())),
                new Update().set("avisoCancelacionPendiente", true), Evento.class);
        return guardado;
    }

    private boolean tieneFlagLegacy(String eventoId) {
        return mongoTemplate.exists(Query.query(Criteria.where("_id").is(eventoId)
                .and("avisoCancelacionPendiente").is(true)), Evento.class);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder solicitudCancelacion(
            String eventoId, String motivo) {
        return put("/eventos/{eventoId}/cancelar", eventoId)
                .header("X-User-Id", "admin-1")
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"" + motivo + "\"}");
    }

    private AvisoOutbox avisoExistente(String id) {
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId(id);
        aviso.setTipo("CANCELACION");
        aviso.setEventoId(id.substring("cancelacion-".length()));
        aviso.setDestino("http://compras.test/eventos-cancelados");
        aviso.setPayload(Map.of("eventoId", aviso.getEventoId()));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(Instant.now());
        aviso.setCreadoEn(Instant.now());
        aviso.setActualizadoEn(Instant.now());
        return aviso;
    }

}
