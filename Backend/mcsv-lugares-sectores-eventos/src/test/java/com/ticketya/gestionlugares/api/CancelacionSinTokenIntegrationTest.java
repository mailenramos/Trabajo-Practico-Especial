package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.outbox.OutboxHttpClient;
import com.ticketya.gestionlugares.outbox.OutboxScheduler;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@SpringBootTest(properties = {
        "outbox.dispatcher.interval=3600000",
        "eventos.finalizacion.interval=3600000",
        "servicio.token="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CancelacionSinTokenIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_cancelacion_sin_token"));
    }

    @Autowired private MockMvc mvc;
    @Autowired private EventoRepository eventos;
    @Autowired private OutboxAvisoRepository outbox;
    @Autowired private OutboxScheduler scheduler;
    @Autowired private ServicioToken token;
    @MockBean private OutboxHttpClient http;

    @Test
    void solicitudYSchedulerDejanLaIntencionPendienteSinReclamar() throws Exception {
        Evento evento = Evento.borrador("org", "lugar", "CABA", "Show", "Descripcion", "MUSICA",
                List.of("Artista"), Instant.parse("2035-01-01T00:00:00Z"), 4, List.of());
        evento.setEstado(EstadoEvento.PUBLICADO);
        eventos.insert(evento);
        assertFalse(token.disponible());

        mvc.perform(put("/eventos/{id}/cancelar", evento.getId())
                        .header("X-User-Id", "admin").header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Motivo\"}"))
                .andExpect(status().isServiceUnavailable());
        scheduler.procesarPendientes();

        Evento persistido = eventos.findById(evento.getId()).orElseThrow();
        AvisoOutbox trabajo = outbox.findById("cancelacion-reservas-" + evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.PUBLICADO, persistido.getEstado());
        assertTrue(persistido.isCancelacionPendiente());
        assertEquals(EstadoAvisoOutbox.PENDIENTE, trabajo.getEstado());
        assertEquals(0, trabajo.getIntentos());
        assertNull(trabajo.getLeaseHasta());
        assertNull(trabajo.getUltimoError());
        assertEquals(1, outbox.count());
        verify(http, never()).enviar(any());
    }
}
