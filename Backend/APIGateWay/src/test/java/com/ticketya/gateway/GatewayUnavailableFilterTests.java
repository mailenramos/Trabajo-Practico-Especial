package com.ticketya.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

class GatewayUnavailableFilterTests {

    private final GatewayUnavailableFilter filter = new GatewayUnavailableFilter(new ObjectMapper());

    @Test
    void convertsConnectionFailureToServiceUnavailable() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/eventos").build());

        filter.filter(exchange, ignored -> Mono.error(new ConnectException("Connection refused"))).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getHeaders().getContentType().toString().contains("application/json"));
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode error = new ObjectMapper().readTree(body);
        assertEquals(503, error.get("status").asInt());
        assertEquals("SERVICIO_NO_DISPONIBLE", error.get("error").asText());
        assertFalse(error.get("mensaje").asText().isBlank());
    }

    @Test
    void propagatesNonConnectionFailures() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/eventos").build());
        IllegalStateException failure = new IllegalStateException("Backend processing failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> filter.filter(exchange, ignored -> Mono.error(failure)).block());

        assertEquals(failure, thrown);
        assertNull(exchange.getResponse().getStatusCode());
    }
}
