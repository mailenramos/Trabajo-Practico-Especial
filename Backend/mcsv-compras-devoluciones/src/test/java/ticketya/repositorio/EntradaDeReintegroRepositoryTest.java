package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.Compra;
import ticketya.modelo.EntradaDeReintegro;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Repositorios que usa la cancelación de un evento. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false"
})
class EntradaDeReintegroRepositoryTest {

    @Autowired EntradaDeReintegroRepository entradasDeReintegro;
    @Autowired CompraRepository compras;
    @Autowired ReintegroRepository reintegros;

    @BeforeEach
    void limpiar() {
        entradasDeReintegro.deleteAll();
        reintegros.deleteAll();
        compras.deleteAll();
    }

    private Compra compra(String eventoId, EstadoCompra estado) {
        Compra c = new Compra();
        c.setClienteId("cliente-1");
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId(eventoId);
        c.setEventoNombre("Recital");
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setTotal(new BigDecimal("30000.00"));
        c = compras.saveAndFlush(c);
        compras.cambiarEstado(c.getId(), EstadoCompra.PENDIENTE, estado);
        return c;
    }

    @Test
    void guardaLasEntradasDeUnReintegroYCualesFaltanDevolver() {
        UUID reintegroId = UUID.randomUUID();
        entradasDeReintegro.save(new EntradaDeReintegro(reintegroId, "e-1"));
        EntradaDeReintegro devuelta = new EntradaDeReintegro(reintegroId, "e-2");
        devuelta.setDevuelta(true);
        entradasDeReintegro.save(devuelta);
        entradasDeReintegro.save(new EntradaDeReintegro(UUID.randomUUID(), "e-3"));

        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(reintegroId))
                .extracting(EntradaDeReintegro::getEntradaId).containsExactly("e-1", "e-2");
        assertThat(entradasDeReintegro.findByReintegroIdAndDevueltaFalseOrderByIdAsc(reintegroId))
                .extracting(EntradaDeReintegro::getEntradaId).containsExactly("e-1");
    }

    @Test
    void unaEntradaNoSeRepiteEnElMismoReintegro() {
        UUID reintegroId = UUID.randomUUID();
        entradasDeReintegro.saveAndFlush(new EntradaDeReintegro(reintegroId, "e-1"));

        assertThatThrownBy(() -> entradasDeReintegro.saveAndFlush(new EntradaDeReintegro(reintegroId, "e-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void buscaSoloLasComprasConfirmadasDelEvento() {
        Compra confirmada = compra("evento-1", EstadoCompra.CONFIRMADA);
        compra("evento-1", EstadoCompra.PENDIENTE);
        compra("evento-1", EstadoCompra.FALLIDA);
        compra("evento-2", EstadoCompra.CONFIRMADA);

        assertThat(compras.findByEventoIdAndEstadoOrderByFechaAsc("evento-1", EstadoCompra.CONFIRMADA))
                .extracting(Compra::getId).containsExactly(confirmada.getId());
    }

    @Test
    void buscaLosReintegrosDeUnaCompraPorOrigen() {
        UUID compraId = UUID.randomUUID();
        Reintegro porCancelacion = reintegros.save(new Reintegro(compraId, UUID.randomUUID(),
                new BigDecimal("15000.00"), "Evento cancelado", OrigenReintegro.CANCELACION_EVENTO));
        reintegros.save(new Reintegro(compraId, UUID.randomUUID(),
                new BigDecimal("8000.50"), "Devolución de entrada e-1", OrigenReintegro.DEVOLUCION));

        assertThat(reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO))
                .extracting(Reintegro::getId).containsExactly(porCancelacion.getId());
    }
}
