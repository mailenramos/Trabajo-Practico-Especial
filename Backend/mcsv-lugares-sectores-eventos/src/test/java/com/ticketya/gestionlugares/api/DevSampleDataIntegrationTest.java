package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.config.DevSampleDataInitializer;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.repository.EventoRepository;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class DevSampleDataIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventoRepository eventoRepository;

    @Autowired
    private DevSampleDataInitializer initializer;

    @Test
    void dosLugaresDeEjemploConAmbosTiposDeSectorSeConsultanPorLaApi() throws Exception {
        MvcResult result = mockMvc.perform(get("/lugares")
                        .header("X-User-Id", "organizador-prueba")
                        .header("X-User-Role", "organizador"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lugares = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, lugares.size());
        for (JsonNode lugar : lugares) {
            Set<String> tipos = new HashSet<>();
            lugar.path("sectores").forEach(sector -> tipos.add(sector.path("tipo").asText()));
            assertTrue(tipos.contains("NUMERADO"), "cada lugar de ejemplo debe incluir un sector numerado");
            assertTrue(tipos.contains("NO_NUMERADO"), "cada lugar de ejemplo debe incluir un sector no numerado");
        }
    }

    @Test
    void cargaCincoEventosConDatosCopiadosDeLosLugaresYFechasFuturas() {
        Map<String, Evento> eventosPorNombre = eventoRepository.findAll().stream()
                .collect(Collectors.toMap(Evento::getNombre, evento -> evento));

        assertEquals(5, eventosPorNombre.size());
        assertEvento(eventosPorNombre.get("Festival de Rock Nacional"), "publicado",
                "507f1f77bcf86cd799439012", "Córdoba", "20000", "8000",
                "550e8400-e29b-41d4-a716-446655440003",
                "550e8400-e29b-41d4-a716-446655440004");
        assertEvento(eventosPorNombre.get("El zorzal"), "publicado",
                "507f1f77bcf86cd799439011", "Buenos Aires", "12000", "7000",
                "550e8400-e29b-41d4-a716-446655440001",
                "550e8400-e29b-41d4-a716-446655440002");
        assertEvento(eventosPorNombre.get("Final del Torneo"), "agotado",
                "507f1f77bcf86cd799439012", "Córdoba", "30000", "12000",
                "550e8400-e29b-41d4-a716-446655440003",
                "550e8400-e29b-41d4-a716-446655440004");
        assertEvento(eventosPorNombre.get("Conferencia de Tecnología"), "borrador",
                "507f1f77bcf86cd799439011", "Buenos Aires", null, null,
                "550e8400-e29b-41d4-a716-446655440001",
                "550e8400-e29b-41d4-a716-446655440002");
        Evento cancelado = eventosPorNombre.get("Recital Cancelado");
        assertEvento(cancelado, "cancelado", "507f1f77bcf86cd799439012", "Córdoba",
                "20000", "8000", "550e8400-e29b-41d4-a716-446655440003",
                "550e8400-e29b-41d4-a716-446655440004");
        assertEquals("Cancelado por motivos de producción.", cancelado.getMotivoCancelacion());
        assertNotNull(cancelado.getCanceladoEn());
        assertTrue(eventosPorNombre.values().stream()
                .noneMatch(Evento::isAvisoCancelacionPendiente));
    }

    @Test
    void unaSegundaEjecucionNoDuplicaNiPisaLosEventos() {
        Evento eventoOriginal = eventoRepository.findById("672a1f77bcf86cd799439001").orElseThrow();
        eventoOriginal.setNombre("Nombre modificado externamente");
        eventoRepository.save(eventoOriginal);

        initializer.run(new DefaultApplicationArguments(new String[0]));

        assertEquals(5, eventoRepository.count());
        assertEquals("Nombre modificado externamente",
                eventoRepository.findById("672a1f77bcf86cd799439001").orElseThrow().getNombre());
        assertTrue(eventoRepository.findAll().stream()
                .noneMatch(Evento::isAvisoCancelacionPendiente));
    }

    private void assertEvento(Evento evento, String estado, String lugarId, String ciudad,
            String precioSector1, String precioSector2, String sectorId1, String sectorId2) {
        assertTrue(evento != null, "el evento de ejemplo debe existir");
        assertEquals("organizador-42", evento.getOrganizadorId());
        assertEquals(4, evento.getLimiteEntradasPorCliente());
        assertEquals(estado, evento.getEstado().getValor());
        assertEquals(lugarId, evento.getLugarId());
        assertEquals(ciudad, evento.getCiudad());
        assertTrue(evento.getFechaHora().isAfter(Instant.now()));
        assertEquals(21, evento.getFechaHora().atZone(ZoneOffset.UTC).getHour());
        assertEquals(sectorId1, evento.getSectores().get(0).getSectorId());
        assertEquals(sectorId2, evento.getSectores().get(1).getSectorId());
        if (precioSector1 == null) {
            assertTrue(evento.getSectores().stream().allMatch(sector -> sector.getPrecio() == null));
            assertNull(evento.getPrecioMinimo());
        } else {
            assertEquals(new BigDecimal(precioSector1),
                    evento.getSectores().get(0).getPrecio());
            assertEquals(new BigDecimal(precioSector2),
                    evento.getSectores().get(1).getPrecio());
            assertEquals(new BigDecimal(precioSector2), evento.getPrecioMinimo());
        }
    }
}
