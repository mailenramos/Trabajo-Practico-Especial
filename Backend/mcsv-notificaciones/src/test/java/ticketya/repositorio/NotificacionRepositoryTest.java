package ticketya.repositorio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class NotificacionRepositoryTest {

    static final Instant AHORA = Instant.parse("2026-11-17T12:00:00Z");

    @Autowired
    NotificacionRepository repositorio;

    static Notificacion aviso(String acontecimiento, TipoNotificacion tipo, String usuario) {
        return Notificacion.nueva(acontecimiento, tipo, usuario, "compra-1", "corr-1", 1,
                AHORA.minus(1, ChronoUnit.MINUTES), Map.of("evento", "Recital", "cantidad", 2), AHORA);
    }

    @Test
    void guardaYRecuperaConDatosYEstadoInicial() {
        Notificacion n = repositorio.saveAndFlush(aviso("ac-1", TipoNotificacion.COMPRA_CONFIRMADA, "u-1"));

        Notificacion leida = repositorio.findByClaveDeduplicacion("ac-1|COMPRA_CONFIRMADA|u-1").orElseThrow();
        assertThat(leida.getId()).isEqualTo(n.getId());
        assertThat(leida.getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(leida.getIntentos()).isZero();
        assertThat(leida.getProximoIntento()).isEqualTo(AHORA);
        assertThat(leida.getDatos()).containsEntry("evento", "Recital").containsEntry("cantidad", 2);
    }

    @Test
    void rechazaDosNotificacionesConLaMismaClave() {
        repositorio.saveAndFlush(aviso("ac-1", TipoNotificacion.COMPRA_CONFIRMADA, "u-1"));

        assertThatThrownBy(() -> repositorio.saveAndFlush(aviso("ac-1", TipoNotificacion.COMPRA_CONFIRMADA, "u-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mismoAcontecimientoConOtroTipoOOtroDestinatarioEsOtraNotificacion() {
        repositorio.saveAndFlush(aviso("ac-1", TipoNotificacion.EVENTO_CANCELADO, "u-1"));
        repositorio.saveAndFlush(aviso("ac-1", TipoNotificacion.EVENTO_CANCELADO, "u-2"));
        repositorio.saveAndFlush(aviso("ac-1", TipoNotificacion.EVENTO_MODIFICADO, "u-1"));

        assertThat(repositorio.count()).isEqualTo(3);
    }
}
