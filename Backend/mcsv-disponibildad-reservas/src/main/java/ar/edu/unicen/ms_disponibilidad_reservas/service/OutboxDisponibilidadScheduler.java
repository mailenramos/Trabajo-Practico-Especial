package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxDisponibilidadScheduler {

    private final OutboxDisponibilidadService outboxService;

    public OutboxDisponibilidadScheduler(OutboxDisponibilidadService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${ms2.outbox.fixed-delay-ms:5000}")
    public void enviarPendientes() {
        outboxService.procesarPendientes();
    }
}
