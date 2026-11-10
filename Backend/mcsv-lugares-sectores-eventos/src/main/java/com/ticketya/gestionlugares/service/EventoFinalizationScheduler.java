package com.ticketya.gestionlugares.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventoFinalizationScheduler {

    private final EventoLifecycleService lifecycleService;

    public EventoFinalizationScheduler(EventoLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${eventos.finalizacion.interval:60000}",
            initialDelayString = "${eventos.finalizacion.interval:60000}")
    public void finalizarVencidos() {
        lifecycleService.finalizarVencidos();
    }
}
