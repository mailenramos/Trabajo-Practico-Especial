package com.ticketya.gateway;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Configuración CORS del Gateway. La aplica la cadena de Spring Security (ver
 * {@link GatewaySecurityConfiguration}) antes de autenticar, así el navegador recibe los headers
 * CORS también en las respuestas 401 y 403 y puede leer el error.
 */
@Configuration
public class GatewayCorsConfiguration {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${gateway.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration(allowedOrigins));
        return source;
    }

    static CorsConfiguration corsConfiguration(String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Authorization", "Content-Type", "Idempotency-Key",
                "X-Correlation-Id", "X-Request-Id"));
        // Permite que el frontend lea el identificador de correlación para reportar errores.
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "X-Request-Id"));
        configuration.setMaxAge(3600L);
        return configuration;
    }
}
