package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically releases reservations whose stored expiration instant has passed. */
@Component
public class ReservaExpirationScheduler {

    private final ReservaService reservaService;

    public ReservaExpirationScheduler(ReservaService reservaService) {
        this.reservaService = reservaService;
    }

    @Scheduled(fixedDelayString = "${ms2.reservas.expiracion.fixed-delay-ms:60000}")
    public void expirarReservasVencidas() {
        reservaService.expirarReservasVencidas();
    }
}
