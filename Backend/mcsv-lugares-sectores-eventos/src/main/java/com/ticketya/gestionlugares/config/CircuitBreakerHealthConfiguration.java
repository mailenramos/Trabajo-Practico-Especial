package com.ticketya.gestionlugares.config;

import java.util.Map;

import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Configuration
public class CircuitBreakerHealthConfiguration {
    @Bean("circuitbreakers")
    CompositeHealthContributor circuitbreakers(CircuitBreakerRegistry registry) {
        Map<String, HealthContributor> estados = Map.of(
                "reservas", indicador(registry, "reservas"),
                "usuarios", indicador(registry, "usuarios"));
        return CompositeHealthContributor.fromMap(estados);
    }

    private HealthIndicator indicador(CircuitBreakerRegistry registry, String nombre) {
        return () -> Health.status(registry.circuitBreaker(nombre).getState().name()).build();
    }
}
