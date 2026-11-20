package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Consultas de compras que usa Recomendaciones (co-compras e historial de preferencias). */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false",
        "compras.avisos.envio-activo=false"
})
class ConsultasDeRecomendacionesTest {

    @Autowired
    CompraRepository compras;

    @BeforeEach
    void limpiar() {
        compras.deleteAll();
    }

    /** Guarda una compra del cliente para el evento, en ese estado, con ese tipo y esa fecha de compra. */
    private Compra compra(String clienteId, String eventoId, EstadoCompra estado, String tipo, Instant fecha) {
        Compra c = new Compra();
        c.setClienteId(clienteId);
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId(eventoId);
        c.setEventoNombre("Evento " + eventoId);
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setTotal(new BigDecimal("1000.00"));
        c.setEstado(estado);
        c.setTipoEvento(tipo);
        c.setFecha(fecha);
        return compras.saveAndFlush(c);
    }

    private Compra confirmada(String clienteId, String eventoId) {
        return compra(clienteId, eventoId, EstadoCompra.CONFIRMADA, null, Instant.now());
    }

    // ------------------------------------------------------------------ co-compras

    @Test
    void coComprasCuentaClientesEnComunOrdenadasDeMayorAMenorYSinElEventoConsultado() {
        // A: c1, c2, c3. B: c1, c2. C: c1. D: c4 (no compró A)
        confirmada("c1", "A");
        confirmada("c2", "A");
        confirmada("c3", "A");
        confirmada("c1", "B");
        confirmada("c2", "B");
        confirmada("c1", "C");
        confirmada("c4", "D");

        assertThat(compras.coCompras("A", Limit.of(10)))
                .containsExactly(new CoCompra("B", 2), new CoCompra("C", 1));
    }

    @Test
    void unClienteConVariasComprasConfirmadasCuentaUnaSolaVez() {
        confirmada("c1", "A");
        confirmada("c1", "A");
        confirmada("c1", "B");
        confirmada("c1", "B");
        confirmada("c1", "B");

        assertThat(compras.coCompras("A", Limit.of(10))).containsExactly(new CoCompra("B", 1));
    }

    @Test
    void lasComprasPendientesOFallidasNoCuentanNiDeUnLadoNiDelOtro() {
        // c1 solo tiene A pendiente: no es comprador de A
        compra("c1", "A", EstadoCompra.PENDIENTE, null, Instant.now());
        confirmada("c1", "B");
        // c2 compró A, pero B y C no están confirmadas
        confirmada("c2", "A");
        compra("c2", "B", EstadoCompra.FALLIDA, null, Instant.now());
        compra("c2", "C", EstadoCompra.PENDIENTE, null, Instant.now());

        assertThat(compras.coCompras("A", Limit.of(10))).isEmpty();
    }

    @Test
    void respetaElLimiteYDesempataPorEventoId() {
        confirmada("c1", "A");
        for (String otro : new String[] {"E", "D", "C", "B"}) {
            confirmada("c1", otro);
        }

        assertThat(compras.coCompras("A", Limit.of(3)))
                .containsExactly(new CoCompra("B", 1), new CoCompra("C", 1), new CoCompra("D", 1));
    }

    @Test
    void unEventoSinComprasDaUnaListaVacia() {
        confirmada("c1", "B");

        assertThat(compras.coCompras("A", Limit.of(10))).isEmpty();
    }

    // ------------------------------------------------------------------ historial por tipo

    private void confirmadaDeTipo(String clienteId, String eventoId, String tipo) {
        compra(clienteId, eventoId, EstadoCompra.CONFIRMADA, tipo, Instant.now());
    }

    @Test
    void elHistorialAgrupaPorTipoDeMayorAMenor() {
        confirmadaDeTipo("c1", "A", "RECITAL");
        confirmadaDeTipo("c1", "B", "RECITAL");
        confirmadaDeTipo("c1", "A", "RECITAL");
        confirmadaDeTipo("c1", "C", "TEATRO");
        confirmadaDeTipo("c2", "D", "TEATRO");

        assertThat(compras.cantidadesPorTipo("c1"))
                .containsExactly(new CantidadPorTipo("RECITAL", 3), new CantidadPorTipo("TEATRO", 1));
    }

    @Test
    void elHistorialIgnoraLasComprasSinTipoYLasNoConfirmadas() {
        confirmadaDeTipo("c1", "A", null);
        compra("c1", "B", EstadoCompra.PENDIENTE, "RECITAL", Instant.now());
        compra("c1", "C", EstadoCompra.FALLIDA, "RECITAL", Instant.now());
        confirmadaDeTipo("c1", "D", "TEATRO");

        assertThat(compras.cantidadesPorTipo("c1")).containsExactly(new CantidadPorTipo("TEATRO", 1));
    }

    @Test
    void aIgualCantidadDesempataPorTipo() {
        confirmadaDeTipo("c1", "A", "TEATRO");
        confirmadaDeTipo("c1", "B", "DEPORTE");

        assertThat(compras.cantidadesPorTipo("c1"))
                .containsExactly(new CantidadPorTipo("DEPORTE", 1), new CantidadPorTipo("TEATRO", 1));
    }

    @Test
    void unClienteSinComprasTieneElHistorialVacio() {
        assertThat(compras.cantidadesPorTipo("nadie")).isEmpty();
        assertThat(compras.eventosComprados("nadie", Limit.of(20))).isEmpty();
    }

    // ------------------------------------------------------------------ eventos comprados

    @Test
    void losEventosCompradosSonDistintosYDelMasRecienteAlMasAntiguo() {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        compra("c1", "A", EstadoCompra.CONFIRMADA, "RECITAL", base);
        compra("c1", "B", EstadoCompra.CONFIRMADA, "TEATRO", base.plusSeconds(60));
        compra("c1", "A", EstadoCompra.CONFIRMADA, "RECITAL", base.plusSeconds(120));
        compra("c2", "Z", EstadoCompra.CONFIRMADA, "RECITAL", base.plusSeconds(180));

        assertThat(compras.eventosComprados("c1", Limit.of(20))).containsExactly("A", "B");
    }

    @Test
    void losEventosCompradosIncluyenComprasSinTipoYExcluyenLasNoConfirmadas() {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        compra("c1", "SIN_TIPO", EstadoCompra.CONFIRMADA, null, base);
        compra("c1", "PENDIENTE", EstadoCompra.PENDIENTE, "RECITAL", base.plusSeconds(60));
        compra("c1", "FALLIDA", EstadoCompra.FALLIDA, "RECITAL", base.plusSeconds(120));

        assertThat(compras.eventosComprados("c1", Limit.of(20))).containsExactly("SIN_TIPO");
    }

    @Test
    void losEventosCompradosRespetanElLimiteQuedandoseConLosMasRecientes() {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        for (int i = 0; i < 25; i++) {
            compra("c1", "evento-" + String.format("%02d", i), EstadoCompra.CONFIRMADA, null, base.plusSeconds(i));
        }

        var eventos = compras.eventosComprados("c1", Limit.of(20));

        assertThat(eventos).hasSize(20);
        assertThat(eventos.get(0)).isEqualTo("evento-24");
        assertThat(eventos.get(19)).isEqualTo("evento-05");
    }

    // ------------------------------------------------------------------ compradores del evento

    @Test
    void losCompradoresConfirmadosSonClientesDistintosYSoloConCompraConfirmada() {
        confirmada("c2", "A");
        confirmada("c1", "A");
        confirmada("c1", "A");                                           // varias compras: un solo cliente
        compra("c3", "A", EstadoCompra.PENDIENTE, null, Instant.now());  // no confirmada
        compra("c4", "A", EstadoCompra.FALLIDA, null, Instant.now());    // no confirmada
        confirmada("c5", "B");                                           // otro evento

        assertThat(compras.compradoresConfirmados("A"))
                .extracting(CompradorDeEvento::clienteId).containsExactly("c1", "c2");
        assertThat(compras.compradoresConfirmados("A")).allSatisfy(c -> {
            assertThat(c.eventoNombre()).isEqualTo("Evento A");
            assertThat(c.eventoFechaInicio()).isEqualTo(Instant.parse("2026-12-01T21:00:00Z"));
        });
    }

    @Test
    void unEventoSinComprasConfirmadasNoTieneCompradores() {
        compra("c1", "A", EstadoCompra.PENDIENTE, null, Instant.now());

        assertThat(compras.compradoresConfirmados("A")).isEmpty();
        assertThat(compras.compradoresConfirmados("inexistente")).isEmpty();
    }
}
