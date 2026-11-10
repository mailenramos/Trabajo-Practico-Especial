package com.ticketya.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;

class GatewayCorsConfigurationTests {

    @Test
    void allowsConfiguredOriginAndApiHeaders() {
        String origin = "http://localhost:5173";
        CorsConfiguration cors = GatewayCorsConfiguration.corsConfiguration(
                "http://localhost:5173, http://localhost:3000");

        assertEquals(origin, cors.checkOrigin(origin));
        assertTrue(cors.checkHttpMethod(HttpMethod.POST).contains(HttpMethod.POST));
        var allowedHeaders = cors.checkHeaders(java.util.List.of(
                "Content-Type", "X-User-Id", "X-User-Role", "Idempotency-Key"));
        assertTrue(allowedHeaders.stream().anyMatch(header -> header.equalsIgnoreCase("x-user-id")));
        assertTrue(allowedHeaders.stream().anyMatch(header -> header.equalsIgnoreCase("x-user-role")));
        assertTrue(allowedHeaders.stream().anyMatch(header -> header.equalsIgnoreCase("idempotency-key")));
    }

    @Test
    void doesNotAllowUnconfiguredOrigin() {
        CorsConfiguration cors = GatewayCorsConfiguration.corsConfiguration("http://localhost:5173");
        assertFalse(cors.getAllowedOrigins().contains("http://malicious.example"));
        org.junit.jupiter.api.Assertions.assertNull(cors.checkOrigin("http://malicious.example"));
    }
}
