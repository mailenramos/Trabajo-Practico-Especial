package com.ticketya.gestionlugares.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Component
public class CircuitBreakerTransiciones {
    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreakerTransiciones.class);

    public CircuitBreakerTransiciones(CircuitBreakerRegistry registry) {
        for (String nombre : List.of("reservas", "usuarios")) {
            registry.circuitBreaker(nombre).getEventPublisher().onStateTransition(event ->
                    LOGGER.warn("Circuito {} cambió de {} a {}", event.getCircuitBreakerName(),
                            event.getStateTransition().getFromState(), event.getStateTransition().getToState()));
        }
    }
}
