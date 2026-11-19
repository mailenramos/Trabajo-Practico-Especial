package com.ticketya.preferencias.api;

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
    void publicaLosTresEndpointsDePreferencias() {
        JsonNode documento = restTemplate.getForObject(
                "http://localhost:" + port + "/v3/api-docs", JsonNode.class);
        JsonNode operaciones = documento.path("paths").path("/preferencias/me");

        assertTrue(operaciones.has("get"));
        assertTrue(operaciones.has("put"));
        assertTrue(operaciones.has("delete"));
    }
}
