package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false"
})
class ReintegroRepositoryTest {

    @Autowired
    ReintegroRepository reintegros;

    @BeforeEach
    void limpiar() {
        reintegros.deleteAll();
    }

    private Reintegro nuevoReintegro(UUID compraId, UUID refundRequestId) {
        return reintegros.saveAndFlush(new Reintegro(
                compraId, refundRequestId, new BigDecimal("10000.00"),
                "Devolución de entrada", OrigenReintegro.DEVOLUCION));
    }

    @Test
    void unReintegroNuevoNaceEnEstadoPendienteSinIntentos() {
        Reintegro r = nuevoReintegro(UUID.randomUUID(), UUID.randomUUID());

        Reintegro guardado = reintegros.findById(r.getId()).orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(guardado.getIntentos()).isZero();
        assertThat(guardado.getPagosReintegroId()).isNull();
    }

    @Test
    void elRefundRequestIdNoSePuedeRepetir() {
        UUID refundRequestId = UUID.randomUUID();
        nuevoReintegro(UUID.randomUUID(), refundRequestId);

        assertThatThrownBy(() -> nuevoReintegro(UUID.randomUUID(), refundRequestId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sePuedeBuscarPorRefundRequestIdYPorCompra() {
        UUID compraId = UUID.randomUUID();
        UUID refundRequestId = UUID.randomUUID();
        nuevoReintegro(compraId, refundRequestId);
        nuevoReintegro(compraId, UUID.randomUUID());

        assertThat(reintegros.findByRefundRequestId(refundRequestId)).isPresent();
        assertThat(reintegros.findByCompraId(compraId)).hasSize(2);
    }

    @Test
    void elMotivoEsObligatorio() {
        assertThatThrownBy(() -> new Reintegro(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.00"), "  ", OrigenReintegro.DEVOLUCION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reintegro(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.00"), null, OrigenReintegro.DEVOLUCION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guardaElUltimoErrorYSeBuscaPorCompraYPorEstado() {
        UUID compraId = UUID.randomUUID();
        Reintegro r = nuevoReintegro(compraId, UUID.randomUUID());
        r.setUltimoError("Pagos respondió 409");
        reintegros.saveAndFlush(r);
        nuevoReintegro(compraId, UUID.randomUUID());

        assertThat(reintegros.findById(r.getId()).orElseThrow().getUltimoError()).isEqualTo("Pagos respondió 409");
        assertThat(reintegros.findByCompraIdOrderByCreadoEnAsc(compraId)).hasSize(2);
        assertThat(reintegros.findByEstadoOrderByCreadoEnDesc(EstadoReintegro.PENDIENTE)).hasSize(2);
        assertThat(reintegros.findAllByOrderByCreadoEnDesc()).hasSize(2);
    }

    @Test
    void unReintegroFallidoGuardaElIdDePagosYSusIntentos() {
        Reintegro r = nuevoReintegro(UUID.randomUUID(), UUID.randomUUID());
        r.setPagosReintegroId("reintegro-de-pagos-1");
        r.setEstado(EstadoReintegro.FALLIDO);
        r.setIntentos(1);
        reintegros.saveAndFlush(r);

        Reintegro guardado = reintegros.findById(r.getId()).orElseThrow();
        assertThat(guardado.getPagosReintegroId()).isEqualTo("reintegro-de-pagos-1");
        assertThat(reintegros.findByEstado(EstadoReintegro.FALLIDO)).hasSize(1);
        assertThat(guardado.getIntentos()).isEqualTo(1);
    }
}
