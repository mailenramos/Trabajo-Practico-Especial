package ar.edu.unicen.ms_fila_virtual.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import ar.edu.unicen.ms_fila_virtual.repository.FilaEventoRepository;

class TurnoSchedulerTests {
    private final FilaEventoRepository filas = mock(FilaEventoRepository.class);
    private final FilaVirtualService servicio = mock(FilaVirtualService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T15:00:00.123456789Z"), ZoneOffset.UTC);
    private final TurnoScheduler scheduler = new TurnoScheduler(filas, servicio, clock);

    @Test
    void procesaTodasLasCandidatasAunqueUnaFalle() {
        LocalDateTime ahora = LocalDateTime.parse("2026-11-17T15:00:00.123456");
        when(filas.findIdsParaProcesar(ahora)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("fallo de prueba")).when(servicio).procesarFila(2L);
        scheduler.procesarTurnos();
        verify(servicio).procesarFila(1L);
        verify(servicio).procesarFila(2L);
        verify(servicio).procesarFila(3L);
    }

    @Test
    void unaFallaDeSeleccionNoIniciaProcesamiento() {
        when(filas.findIdsParaProcesar(any())).thenThrow(new IllegalStateException("base no disponible"));
        assertDoesNotThrow(scheduler::procesarTurnos);
        verifyNoInteractions(servicio);
    }

    @Test
    void usaElIntervaloFixedDelayConfigurado() throws Exception {
        Scheduled scheduled = TurnoScheduler.class.getMethod("procesarTurnos").getAnnotation(Scheduled.class);
        assertEquals("${ms7.turnos.fixed-delay-ms:60000}", scheduled.fixedDelayString());
    }
}
