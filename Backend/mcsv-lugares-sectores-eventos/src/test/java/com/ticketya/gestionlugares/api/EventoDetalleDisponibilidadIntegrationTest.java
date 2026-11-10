package com.ticketya.gestionlugares.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.AsientoDisponibleResponse;
import com.ticketya.gestionlugares.reservas.dto.DisponibilidadEventoResponse;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadResponse;

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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventoDetalleDisponibilidadIntegrationTest {
    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl().replace("/test", "/ticketya_lugares"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EventoRepository eventoRepository;
    @Autowired private LugarRepository lugarRepository;
    @MockBean private ReservasClient reservasClient;

    @BeforeEach
    void limpiarBase() {
        eventoRepository.deleteAll();
        lugarRepository.deleteAll();
    }

    @Test
    void detalleHttpCombinaDisponibilidadPorSectorIdYDejaNullLosSectoresFaltantes() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(reservasClient.consultarDisponibilidad(evento.getId())).thenReturn(new DisponibilidadEventoResponse(
                evento.getId(), List.of(
                        new SectorDisponibilidadResponse("sector-b", TipoSector.NO_NUMERADO, 50, 17, null),
                        new SectorDisponibilidadResponse("sector-a", TipoSector.NUMERADO, 2, 1,
                                List.of(new AsientoDisponibleResponse(1, 2))))));

        mockMvc.perform(get("/eventos/" + evento.getId())
                        .header("X-User-Id", "cliente-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].sectorId").value("sector-a"))
                .andExpect(jsonPath("$.sectores[0].precio").value(12000))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.capacidadTotal").value(2))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.cantidadDisponible").value(1))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.asientosDisponibles[0].fila").value(1))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.asientosDisponibles[0].numero").value(2))
                .andExpect(jsonPath("$.sectores[1].disponibilidad.cantidadDisponible").value(17))
                .andExpect(jsonPath("$.sectores[2].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        verify(reservasClient).consultarDisponibilidad(evento.getId());
    }

    @Test
    void detalleSigueRespondiendo200SiReservasFalla() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(reservasClient.consultarDisponibilidad(evento.getId()))
                .thenThrow(new ReservasClientException("Reservas no responde"));

        mockMvc.perform(get("/eventos/" + evento.getId())
                        .header("X-User-Id", "cliente-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Festival de prueba"))
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sectores[1].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void estadosQueNoConsultanReservasDevuelvenDisponibilidadNula() throws Exception {
        Lugar lugar = crearLugar();
        Evento borrador = crearEvento(lugar, EstadoEvento.BORRADOR);
        mockMvc.perform(get("/eventos/" + borrador.getId())
                        .header("X-User-Id", "org-1").header("X-User-Role", "ORGANIZADOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));

        Evento cancelado = crearEvento(lugar, EstadoEvento.CANCELADO);
        mockMvc.perform(get("/eventos/" + cancelado.getId())
                        .header("X-User-Id", "cliente-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));

        Evento finalizado = crearEvento(lugar, EstadoEvento.FINALIZADO);
        mockMvc.perform(get("/eventos/" + finalizado.getId())
                        .header("X-User-Id", "admin-1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(reservasClient);
    }

    private Evento guardarEventoPublicado() {
        Lugar lugar = crearLugar();
        Evento evento = crearEvento(lugar, EstadoEvento.PUBLICADO);
        return eventoRepository.save(evento);
    }

    private Lugar crearLugar() {
        return lugarRepository.save(new Lugar(null, "Estadio", "Av. Central", "CABA", -34.0, -58.0, List.of()));
    }

    private Evento crearEvento(Lugar lugar, EstadoEvento estado) {
        Evento evento = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), "Festival de prueba",
                "Descripción", "MUSICA", List.of("Artista"), Instant.parse("2027-05-16T00:00:00Z"), 4,
                List.of(new EventoSector("sector-a", "Platea", TipoSector.NUMERADO, 1, 2, null,
                                new java.math.BigDecimal("12000")),
                        new EventoSector("sector-b", "Campo", TipoSector.NO_NUMERADO, null, null, 50,
                                new java.math.BigDecimal("8000")),
                        new EventoSector("sector-c", "Balcón", TipoSector.NUMERADO, 1, 1, null,
                                new java.math.BigDecimal("6000"))));
        evento.setEstado(estado);
        return eventoRepository.save(evento);
    }
}
