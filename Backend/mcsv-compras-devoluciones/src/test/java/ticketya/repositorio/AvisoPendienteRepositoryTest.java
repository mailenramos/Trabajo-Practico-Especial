package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.TipoAviso;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outbox de avisos: clave única (acontecimiento, tipo, destinatario) y alta dentro de la transacción. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false",
        "compras.avisos.envio-activo=false"
})
class AvisoPendienteRepositoryTest {

    @Autowired
    AvisoPendienteRepository avisos;

    @Autowired
    TransactionTemplate transaccion;

    @BeforeEach
    void limpiar() {
        avisos.deleteAll();
    }

    private void registrar(String acontecimientoId, TipoAviso tipo, String usuarioId) {
        transaccion.executeWithoutResult(t -> avisos.registrar(acontecimientoId, tipo.name(), usuarioId, "recurso-1",
                UUID.randomUUID().toString(), "{\"evento\":\"Recital\"}", Instant.now()));
    }

    @Test
    void unAvisoNuevoQuedaPendienteSinIntentosYListoParaEnviar() {
        Instant antes = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        registrar("compra-confirmada-1", TipoAviso.COMPRA_CONFIRMADA, "c1");

        AvisoPendiente a = avisos.findByAcontecimientoIdAndTipoAndUsuarioId(
                "compra-confirmada-1", TipoAviso.COMPRA_CONFIRMADA, "c1").orElseThrow();
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
        assertThat(a.getIntentos()).isZero();
        assertThat(a.getRecursoId()).isEqualTo("recurso-1");
        assertThat(a.getCorrelacionId()).isNotBlank();
        assertThat(a.getDatos()).contains("Recital");
        assertThat(a.getProximoIntento()).isAfterOrEqualTo(antes);
        assertThat(a.getCreadoEn()).isAfterOrEqualTo(antes);
        assertThat(a.getUltimoError()).isNull();
    }

    @Test
    void dosCompradoresDelMismoAcontecimientoConviven() {
        registrar("evento-cancelado-e1", TipoAviso.EVENTO_CANCELADO, "c1");
        registrar("evento-cancelado-e1", TipoAviso.EVENTO_CANCELADO, "c2");

        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_CANCELADO))
                .extracting(AvisoPendiente::getUsuarioId).containsExactly("c1", "c2");
    }

    @Test
    void elMismoAvisoRepetidoNoCreaOtroNiLanzaError() {
        registrar("compra-confirmada-1", TipoAviso.COMPRA_CONFIRMADA, "c1");
        registrar("compra-confirmada-1", TipoAviso.COMPRA_CONFIRMADA, "c1");

        assertThat(avisos.count()).isEqualTo(1);
    }

    @Test
    void elMismoAcontecimientoConOtroTipoEsOtroAviso() {
        registrar("x-1", TipoAviso.COMPRA_CONFIRMADA, "c1");
        registrar("x-1", TipoAviso.ENTRADAS_DISPONIBLES, "c1");

        assertThat(avisos.count()).isEqualTo(2);
    }

    @Test
    void sinTransaccionNoSeRegistra() {
        assertThatThrownBy(() -> avisos.registrar("a", TipoAviso.COMPRA_CONFIRMADA.name(), "c1", "r",
                UUID.randomUUID().toString(), "{}", Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(avisos.count()).isZero();
    }

    @Test
    void siLaTransaccionSeRevierteNoQuedaElAviso() {
        assertThatThrownBy(() -> transaccion.executeWithoutResult(t -> {
            avisos.registrar("compra-confirmada-1", TipoAviso.COMPRA_CONFIRMADA.name(), "c1", "r",
                    UUID.randomUUID().toString(), "{}", Instant.now());
            throw new IllegalStateException("falla la operación de negocio");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(avisos.count()).isZero();
    }
}
