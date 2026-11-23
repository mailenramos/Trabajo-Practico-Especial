package com.ticketya.preferencias.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.fasterxml.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void publicaLosEndpointsDePreferenciasYRecomendaciones() {
        JsonNode documento = restTemplate.getForObject(
                "http://localhost:" + port + "/v3/api-docs", JsonNode.class);
        JsonNode operaciones = documento.path("paths").path("/preferencias/me");

        assertTrue(operaciones.has("get"));
        assertTrue(operaciones.has("put"));
        assertTrue(operaciones.has("delete"));
        assertTrue(documento.path("paths").path("/recomendaciones/me").has("get"));
    }

    @Test
    void documentaSeguridadDelGatewayYDelCallbackInterno() {
        JsonNode documento = restTemplate.getForObject(
                "http://localhost:" + port + "/v3/api-docs", JsonNode.class);
        JsonNode bearer = documento.path("components").path("securitySchemes").path("bearerAuth");
        assertEquals("http", bearer.path("type").asText());
        assertEquals("bearer", bearer.path("scheme").asText());
        assertEquals("JWT", bearer.path("bearerFormat").asText());

        JsonNode preferencias = documento.path("paths").path("/preferencias/me");
        for (String metodo : new String[] {"get", "put", "delete"}) {
            assertTrue(preferencias.path(metodo).path("security").get(0).has("bearerAuth"));
        }
        assertTrue(documento.path("paths").path("/recomendaciones/me").path("get")
                .path("security").get(0).has("bearerAuth"));
        JsonNode interno = documento.path("paths").path("/internal/preferencias/eventos-publicados").path("post");
        assertFalse(interno.has("security"));
        assertTrue(interno.path("parameters").findValuesAsText("name").contains("X-Service-Token"));
        for (JsonNode operaciones : documento.path("paths")) {
            for (JsonNode operacion : operaciones) {
                for (String estado : new String[] {"400", "401", "403", "503"}) {
                    JsonNode respuesta = operacion.path("responses").path(estado);
                    if (!respuesta.isMissingNode()) {
                        assertEquals("#/components/schemas/ApiError", respuesta.path("content")
                                .path("application/json").path("schema").path("$ref").asText());
                    }
                }
            }
        }
    }
}
