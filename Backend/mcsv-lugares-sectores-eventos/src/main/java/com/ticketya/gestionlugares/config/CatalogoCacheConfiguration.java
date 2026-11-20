package com.ticketya.gestionlugares.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

@Configuration
@EnableCaching
@EnableConfigurationProperties(CatalogoCacheProperties.class)
public class CatalogoCacheConfiguration {

    public static final String BUSQUEDA_EVENTOS = "busquedaEventos";

    @Bean
    public Ticker catalogoCacheTicker() {
        return Ticker.systemTicker();
    }

    @Bean
    public CaffeineCacheManager cacheManager(CatalogoCacheProperties properties, Ticker catalogoCacheTicker) {
        CaffeineCacheManager manager = new CaffeineCacheManager(BUSQUEDA_EVENTOS);
        manager.setAllowNullValues(false);
        manager.setCaffeine(Caffeine.newBuilder().maximumSize(properties.maximoEntradas())
                .expireAfterWrite(properties.ttl()).ticker(catalogoCacheTicker));
        return manager;
    }
}
