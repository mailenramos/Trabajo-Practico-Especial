package com.ticketya.usuarios.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties(prefix = "usuarios.jwt")
public record JwtProperties(String secret, String issuer, Duration expiracion) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET es obligatorio y debe tener al menos 32 bytes UTF-8 "
                    + "(el mismo valor que usa el API Gateway)");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("JWT_ISSUER no puede estar vacío");
        }
        if (expiracion == null || expiracion.isNegative() || expiracion.isZero()) {
            throw new IllegalStateException("JWT_EXPIRATION debe ser una duración positiva, por ejemplo PT1H");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=<oculto>, issuer=" + issuer + ", expiracion=" + expiracion + "]";
    }
}
