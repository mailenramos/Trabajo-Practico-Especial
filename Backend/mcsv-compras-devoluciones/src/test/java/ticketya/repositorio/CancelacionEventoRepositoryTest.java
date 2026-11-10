package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.CancelacionEvento;
import ticketya.modelo.EstadoCancelacion;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false"
})
class CancelacionEventoRepositoryTest {

    @Autowired
    CancelacionEventoRepository cancelaciones;

    @BeforeEach
    void limpiar() {
        cancelaciones.deleteAll();
    }

    private CancelacionEvento nueva(String eventoId) {
        return cancelaciones.saveAndFlush(new CancelacionEvento(eventoId, "Lluvia", Instant.now()));
    }

    @Test
    void unaCancelacionNuevaQuedaEnProceso() {
        nueva("evento-1");

        CancelacionEvento c = cancelaciones.findById("evento-1").orElseThrow();
        assertThat(c.getEstado()).isEqualTo(EstadoCancelacion.EN_PROCESO);
        assertThat(c.getTomadaEn()).isEqualTo(c.getRecibidaEn());
        assertThat(c.getEntradasMarcadas()).isNull();
    }

    @Test
    void elMismoEventoNoSeRegistraDosVeces() {
        nueva("evento-1");

        assertThatThrownBy(() -> nueva("evento-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(cancelaciones.count()).isEqualTo(1);
    }

    @Test
    void soloUnPedidoRetomaUnaCancelacionVencida() {
        Instant tomada = nueva("evento-1").getTomadaEn();
        Instant ahora = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);

        assertThat(cancelaciones.retomar("evento-1", tomada, ahora)).isEqualTo(1);
        assertThat(cancelaciones.retomar("evento-1", tomada, ahora)).isZero();
        assertThat(cancelaciones.findById("evento-1").orElseThrow().getTomadaEn()).isEqualTo(ahora);
    }

    @Test
    void unaCancelacionProcesadaNoSeRetoma() {
        Instant tomada = nueva("evento-1").getTomadaEn();
        cancelaciones.terminar("evento-1");

        assertThat(cancelaciones.retomar("evento-1", tomada, Instant.now())).isZero();
        assertThat(cancelaciones.findById("evento-1").orElseThrow().getEstado())
                .isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void lasEntradasMarcadasSeAcumulan() {
        nueva("evento-1");

        cancelaciones.registrarMarcacion("evento-1", 4);
        cancelaciones.registrarMarcacion("evento-1", 0);

        assertThat(cancelaciones.findById("evento-1").orElseThrow().getEntradasMarcadas()).isEqualTo(4);
    }

    @Test
    void unaMarcacionExitosaQuedaHechaYUnaFallidaNo() {
        nueva("evento-1");
        nueva("evento-2");

        cancelaciones.registrarMarcacion("evento-1", 4);
        cancelaciones.registrarFallaDeMarcacion("evento-2", "MARCACION: Entradas no responde");

        var exitosa = cancelaciones.findById("evento-1").orElseThrow();
        assertThat(exitosa.isMarcacionHecha()).isTrue();
        assertThat(exitosa.getUltimoError()).isNull();
        var fallida = cancelaciones.findById("evento-2").orElseThrow();
        assertThat(fallida.isMarcacionHecha()).isFalse();
        assertThat(fallida.getEntradasMarcadas()).isZero();
        assertThat(fallida.getUltimoError()).startsWith("MARCACION");
    }
}
