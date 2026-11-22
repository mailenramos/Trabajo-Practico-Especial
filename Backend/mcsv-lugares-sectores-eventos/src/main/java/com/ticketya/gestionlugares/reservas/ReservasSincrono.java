package com.ticketya.gestionlugares.reservas;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Component
public class ReservasSincrono {
    private final CircuitBreaker circuitBreaker;

    public ReservasSincrono(CircuitBreakerRegistry registry) {
        this.circuitBreaker = registry.circuitBreaker("reservas");
    }

    public <T> T ejecutar(Supplier<T> llamada) {
        try {
            return circuitBreaker.executeSupplier(llamada);
        } catch (CallNotPermittedException exception) {
            throw new ReservasClientException("Circuito de Reservas abierto", exception);
        }
    }

    public void ejecutar(Runnable llamada) {
        ejecutar(() -> {
            llamada.run();
            return null;
        });
    }
}
