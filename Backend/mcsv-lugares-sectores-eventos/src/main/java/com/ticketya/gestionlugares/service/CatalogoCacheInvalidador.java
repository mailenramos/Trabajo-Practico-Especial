package com.ticketya.gestionlugares.service;

import java.util.Objects;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ticketya.gestionlugares.config.CatalogoCacheConfiguration;

@Component
public class CatalogoCacheInvalidador {

    private final Cache cache;

    public CatalogoCacheInvalidador(CacheManager cacheManager) {
        this.cache = Objects.requireNonNull(cacheManager.getCache(CatalogoCacheConfiguration.BUSQUEDA_EVENTOS),
                "No está configurada la caché de búsqueda del catálogo");
    }

    public void invalidar() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("No se puede invalidar el catálogo después del commit");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.clear();
                }
            });
        } else {
            cache.clear();
        }
    }
}
