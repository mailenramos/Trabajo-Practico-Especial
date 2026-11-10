package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
}
