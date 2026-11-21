package com.ticketya.msvc.entradas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "entradas")
public record EntradasProperties(Seguridad seguridad, Ms1 ms1) {

    public EntradasProperties {
        seguridad = seguridad != null ? seguridad : new Seguridad(null);
        ms1 = ms1 != null ? ms1 : new Ms1(null, null, null);
    }

    /** serviceToken: secreto compartido para X-Service-Token (vacío = validación desactivada). */
    public record Seguridad(String serviceToken) {
        @Override
        public String toString() {
            return "Seguridad[serviceToken=<oculto>]";
        }
    }

    /** Lugares, sectores y eventos: autorización del personal de acceso. */
    public record Ms1(String url, Duration connectTimeout, Duration readTimeout) {
        public Ms1 {
            url = url == null || url.isBlank() ? "http://localhost:8081" : url;
            connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(1);
            readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
        }
    }
}
