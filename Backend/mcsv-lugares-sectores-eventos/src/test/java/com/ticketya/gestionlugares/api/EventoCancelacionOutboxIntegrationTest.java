package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
import com.ticketya.gestionlugares.outbox.OutboxHttpClient;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;

@SpringBootTest(properties = {
        "outbox.dispatcher.interval=3600000",
        "eventos.cancelacion.reenvio-interval=3600000"
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
        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenReturn(204);

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cancelado"))
                .andExpect(jsonPath("$.avisoCancelacionPendiente").value(false));

        Evento persistido = eventoRepository.findById(evento.getId()).orElseThrow();
        AvisoOutbox aviso = outboxRepository.findById("cancelacion-" + evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.CANCELADO, persistido.getEstado());
        assertFalse(persistido.isAvisoCancelacionPendiente());
        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso.getEstado());
        assertEquals(evento.getId(), aviso.getEventoId());
        assertTrue(aviso.getDestino().endsWith("/eventos-cancelados"));
        assertEquals(evento.getId(), aviso.getPayload().get("eventoId"));
        assertEquals("Cierre preventivo", aviso.getPayload().get("motivo"));
        assertNotNull(aviso.getPayload().get("canceladoEn"));
        verify(reservasClient).cambiarEstadoVenta(evento.getId(), EstadoVentaReservas.CANCELADA);
        verify(outboxHttpClient).enviar(any(AvisoOutbox.class));
    }

    @Test
    void falloAlInsertarAvisoRevierteElCambioDelEvento() throws Exception {
        Evento evento = guardarEventoPublicado();
        outboxRepository.insert(avisoExistente("cancelacion-" + evento.getId()));

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isConflict());

        Evento persistido = eventoRepository.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO, persistido.getEstado());
        assertFalse(persistido.isAvisoCancelacionPendiente());
        assertEquals(1, outboxRepository.count());
        verify(outboxHttpClient, never()).enviar(any(AvisoOutbox.class));
    }

    @Test
    void falloDeComprasMantieneRespuesta200YAvisoPendiente() throws Exception {
        Evento evento = guardarEventoPublicado();
        doThrow(new ResourceAccessException("Compras no responde",
                new ConnectException("connection refused")))
                .when(outboxHttpClient).enviar(any(AvisoOutbox.class));

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cancelado"))
                .andExpect(jsonPath("$.avisoCancelacionPendiente").value(false));

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
        when(outboxHttpClient.enviar(any(AvisoOutbox.class))).thenReturn(200);

        mockMvc.perform(solicitudCancelacion(evento.getId(), "Cierre preventivo"))
                .andExpect(status().isOk());
        mockMvc.perform(solicitudCancelacion(evento.getId(), "Otro motivo"))
                .andExpect(status().isConflict());

        assertEquals(1, outboxRepository.count());
        verify(reservasClient).cambiarEstadoVenta(evento.getId(), EstadoVentaReservas.CANCELADA);
        verify(outboxHttpClient).enviar(any(AvisoOutbox.class));
    }

    private Evento guardarEventoPublicado() {
        Evento evento = Evento.borrador("organizador-1", "lugar-1", "CABA", "Show", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("sector-1", "Platea", TipoSector.NUMERADO, 1, 1, null, null)));
        evento.setEstado(EstadoEvento.PUBLICADO);
        return eventoRepository.save(evento);
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
