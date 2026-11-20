package com.ticketya.gestionlugares.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CatalogoCacheConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CatalogoCacheConfiguration.class);

    @Test
    void aceptaValoresPositivos() {
        context.withPropertyValues("catalogo.cache.ttl=30s", "catalogo.cache.maximo-entradas=1000")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void rechazaTtlYLimiteNoPositivos() {
        for (String[] propiedades : new String[][] {
                {"catalogo.cache.ttl=0s", "catalogo.cache.maximo-entradas=1000"},
                {"catalogo.cache.ttl=-1s", "catalogo.cache.maximo-entradas=1000"},
                {"catalogo.cache.ttl=30s", "catalogo.cache.maximo-entradas=0"},
                {"catalogo.cache.ttl=30s", "catalogo.cache.maximo-entradas=-1"}
        }) {
            context.withPropertyValues(propiedades).run(result -> assertThat(result).hasFailed());
        }
    }
}
