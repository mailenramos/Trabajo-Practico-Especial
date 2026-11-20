package com.ticketya.gestionlugares.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalogo.cache")
public record CatalogoCacheProperties(Duration ttl, long maximoEntradas) {

    public CatalogoCacheProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("catalogo.cache.ttl debe ser mayor que cero");
        }
        if (maximoEntradas < 1) {
            throw new IllegalArgumentException("catalogo.cache.maximo-entradas debe ser mayor que cero");
        }
    }
}
