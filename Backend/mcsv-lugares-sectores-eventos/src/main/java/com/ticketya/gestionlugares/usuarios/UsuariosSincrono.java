package com.ticketya.gestionlugares.usuarios;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.ticketya.gestionlugares.service.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Component
public class UsuariosSincrono {
    private final CircuitBreaker circuitBreaker;

    public UsuariosSincrono(CircuitBreakerRegistry registry) {
        this.circuitBreaker = registry.circuitBreaker("usuarios");
    }

    public <T> T ejecutar(Supplier<T> llamada) {
        try {
            return circuitBreaker.executeSupplier(llamada);
        } catch (CallNotPermittedException exception) {
            throw new ServiceUnavailableException("Servicio de usuarios no disponible", exception);
        }
    }
}
