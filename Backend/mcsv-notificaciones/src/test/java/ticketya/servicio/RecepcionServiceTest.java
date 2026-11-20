package ticketya.servicio;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;
import ticketya.repositorio.NotificacionRepository;
import ticketya.web.dto.AvisoPedido;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecepcionServiceTest {

    static final Instant AHORA = Instant.parse("2026-11-17T12:00:00Z");

    @Test
    void siDosReentregasLleganALaVezDevuelveLaQueGanoElInsert() {
        NotificacionRepository repositorio = mock(NotificacionRepository.class);
        Notificacion ganadora = Notificacion.nueva("ac-1", TipoNotificacion.COMPRA_CONFIRMADA, "u-1", "c-1",
                "corr-1", 1, AHORA, Map.of(), AHORA);
        String clave = ganadora.getClaveDeduplicacion();
        when(repositorio.findByClaveDeduplicacion(clave)).thenReturn(Optional.empty(), Optional.of(ganadora));
        when(repositorio.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("clave duplicada"));

        RecepcionService servicio = new RecepcionService(repositorio, Clock.fixed(AHORA, ZoneOffset.UTC));
        AvisoPedido aviso = new AvisoPedido("ac-1", "COMPRA_CONFIRMADA", 1, AHORA, "c-1", "u-1", "corr-1", Map.of());

        RecepcionService.Recepcion recepcion = servicio.recibir(aviso);

        assertThat(recepcion.duplicado()).isTrue();
        assertThat(recepcion.notificacion()).isSameAs(ganadora);
    }
}
