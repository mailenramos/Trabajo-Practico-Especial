package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.repository.LugarRepository;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LugarApiEndToEndTest {

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
    private LugarRepository lugarRepository;

    @BeforeEach
    void limpiarBase() {
        lugarRepository.deleteAll();
    }

    @Test
    void creaLugarYSectoresYLuegoLosConsultaPorLaApi() throws Exception {
        mockMvc.perform(get("/lugares")
                        .header("X-User-Id", "organizador-e2e")
                        .header("X-User-Role", "organizador"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        MvcResult altaLugar = mockMvc.perform(post("/lugares")
                        .header("X-User-Id", "admin-e2e")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Teatro E2E","direccion":"Calle de prueba 100","ciudad":"Córdoba","latitud":-31.42,"longitud":-64.19}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(altaLugar.getResponse().getContentAsString()).path("id").asText();
        assertEquals(24, id.length());

        mockMvc.perform(post("/lugares/" + id + "/sectores")
                        .header("X-User-Id", "admin-e2e")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Platea","tipo":"NUMERADO","filas":3,"asientosPorFila":8}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("NUMERADO"));

        mockMvc.perform(post("/lugares/" + id + "/sectores")
                        .header("X-User-Id", "admin-e2e")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Campo","tipo":"NO_NUMERADO","capacidad":75}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacidad").value(75));

        MvcResult lista = mockMvc.perform(get("/lugares")
                        .header("X-User-Id", "organizador-e2e")
                        .header("X-User-Role", "organizador"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lugares = objectMapper.readTree(lista.getResponse().getContentAsString());
        assertEquals(1, lugares.size());
        assertEquals(id, lugares.get(0).path("id").asText());

        mockMvc.perform(get("/lugares/" + id)
                        .header("X-User-Id", "admin-e2e")
                        .header("X-User-Role", "administrador"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[1].nombre").value("Campo"));

        MvcResult consultaSectores = mockMvc.perform(get("/lugares/" + id + "/sectores")
                        .header("X-User-Id", "organizador-e2e")
                        .header("X-User-Role", "organizador"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sectores = objectMapper.readTree(consultaSectores.getResponse().getContentAsString());
        Set<String> tipos = new HashSet<>();
        sectores.forEach(sector -> tipos.add(sector.path("tipo").asText()));
        assertTrue(tipos.contains("NUMERADO"));
        assertTrue(tipos.contains("NO_NUMERADO"));
    }

    @Test
    void lasConsultasYAltasResponden404CuandoFaltaElLugar() throws Exception {
        String idInexistente = "507f1f77bcf86cd799439011";

        mockMvc.perform(get("/lugares/" + idInexistente)
                        .header("X-User-Id", "organizador-e2e")
                        .header("X-User-Role", "organizador"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/lugares/" + idInexistente + "/sectores")
                        .header("X-User-Id", "organizador-e2e")
                        .header("X-User-Role", "organizador"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/lugares/" + idInexistente + "/sectores")
                        .header("X-User-Id", "admin-e2e")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Platea","tipo":"NUMERADO","filas":3,"asientosPorFila":8}
                                """))
                .andExpect(status().isNotFound());
    }
}
