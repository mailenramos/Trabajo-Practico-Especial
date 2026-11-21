package ar.edu.unicen.ms_fila_virtual.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.edu.unicen.ms_fila_virtual.repository.FilaEventoRepository;

@Component
public class TurnoScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnoScheduler.class);

    private final FilaEventoRepository filas;
    private final FilaVirtualService servicio;
    private final Clock clock;

    public TurnoScheduler(FilaEventoRepository filas, FilaVirtualService servicio, Clock clock) {
        this.filas = filas;
        this.servicio = servicio;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ms7.turnos.fixed-delay-ms:60000}")
    public void procesarTurnos() {
        LocalDateTime ahora = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        List<Long> candidatas;
        try {
            candidatas = filas.findIdsParaProcesar(ahora);
        } catch (RuntimeException exception) {
            LOGGER.error("No se pudieron seleccionar las filas para procesar; se reintentará en el próximo barrido.", exception);
            return;
        }
        for (Long filaId : candidatas) {
            try {
                servicio.procesarFila(filaId);
            } catch (RuntimeException exception) {
                LOGGER.error("No se pudo procesar la fila {}; se continuará con las demás.", filaId, exception);
            }
        }
    }
}
