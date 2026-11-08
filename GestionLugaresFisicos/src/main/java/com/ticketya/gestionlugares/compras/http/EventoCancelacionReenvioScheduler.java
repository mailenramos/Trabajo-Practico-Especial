package com.ticketya.gestionlugares.compras.http;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ticketya.gestionlugares.service.EventoLifecycleService;

@Component
public class EventoCancelacionReenvioScheduler {

    private final EventoLifecycleService lifecycleService;

    public EventoCancelacionReenvioScheduler(EventoLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${eventos.cancelacion.reenvio-interval:60000}",
            initialDelayString = "${eventos.cancelacion.reenvio-interval:60000}")
    public void reenviarAvisosPendientes() {
        lifecycleService.reenviarAvisosCancelacionPendientes();
    }
}
