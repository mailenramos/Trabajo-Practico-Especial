package com.ticketya.gestionlugares.reservas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

class ReservasSincronoTest {
    @Test
    void llamadaExitosaEnSemiabiertoCierraElCircuito() {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(30))
                .permittedNumberOfCallsInHalfOpenState(1).build();
        var registry = CircuitBreakerRegistry.of(config);
        var circuito = registry.circuitBreaker("reservas");
        var cliente = new ReservasSincrono(registry);
        List<CircuitBreaker.State> transiciones = new ArrayList<>();
        circuito.getEventPublisher().onStateTransition(event ->
                transiciones.add(event.getStateTransition().getToState()));

        for (int i = 0; i < 2; i++) {
            assertThrows(ReservasClientException.class,
                    () -> cliente.ejecutar(() -> { throw new ReservasClientException("timeout"); }));
        }
        assertEquals(CircuitBreaker.State.OPEN, circuito.getState());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertEquals("ok", cliente.ejecutar(() -> "ok"));
            assertEquals(CircuitBreaker.State.CLOSED, circuito.getState());
        });
        assertTrue(transiciones.contains(CircuitBreaker.State.HALF_OPEN));
    }
}
