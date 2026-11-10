package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.ClaveIdempotencia;
import ticketya.modelo.ClaveIdempotenciaId;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false"
})
class CompraRepositoryTest {

    @Autowired
    CompraRepository compras;

    @Autowired
    ClaveIdempotenciaRepository claves;

    @BeforeEach
    void limpiar() {
        compras.deleteAll();
        claves.deleteAll();
    }

    private Compra nuevaCompra(String clienteId) {
        Compra c = new Compra();
        c.setClienteId(clienteId);
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId("evento-1");
        c.setEventoNombre("Recital de prueba");
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setTotal(new BigDecimal("30000.00"));
        return compras.save(c);
    }

    @Test
    void unaCompraNuevaNaceEnEstadoPendiente() {
        Compra c = nuevaCompra("cliente-1");

        assertThat(compras.findById(c.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoCompra.PENDIENTE);
    }

    @Test
    void cambiarEstadoDesdePendienteFunciona() {
        Compra c = nuevaCompra("cliente-1");

        int filas = compras.cambiarEstado(c.getId(), EstadoCompra.PENDIENTE, EstadoCompra.CONFIRMADA);

        assertThat(filas).isEqualTo(1);
        assertThat(compras.findById(c.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoCompra.CONFIRMADA);
    }

    @Test
    void unaCompraYaResueltaNoCambiaDeNuevo() {
        Compra c = nuevaCompra("cliente-1");
        compras.cambiarEstado(c.getId(), EstadoCompra.PENDIENTE, EstadoCompra.CONFIRMADA);

        int filas = compras.cambiarEstado(c.getId(), EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA);

        assertThat(filas).isZero();
        assertThat(compras.findById(c.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoCompra.CONFIRMADA);
    }

    @Test
    void conVariosHilosALaVezSoloUnoResuelveLaCompra() throws Exception {
        Compra c = nuevaCompra("cliente-1");
        int hilos = 20;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();

        for (int i = 0; i < hilos; i++) {
            resultados.add(pool.submit(() -> {
                largada.await();
                return compras.cambiarEstado(c.getId(), EstadoCompra.PENDIENTE, EstadoCompra.CONFIRMADA);
            }));
        }
        largada.countDown();

        int exitosos = 0;
        for (Future<Integer> f : resultados) {
            exitosos += f.get();
        }
        pool.shutdown();

        assertThat(exitosos).isEqualTo(1);
        assertThat(compras.findById(c.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoCompra.CONFIRMADA);
    }

    @Test
    void cadaClienteVeSoloSusCompras() {
        nuevaCompra("cliente-1");
        nuevaCompra("cliente-1");
        nuevaCompra("cliente-2");

        assertThat(compras.findByClienteIdOrderByFechaDesc("cliente-1")).hasSize(2);
        assertThat(compras.findByClienteIdOrderByFechaDesc("cliente-2")).hasSize(1);
        assertThat(compras.findByClienteIdOrderByFechaDesc("cliente-3")).isEmpty();
    }

    @Test
    void laMismaClaveDelMismoClienteNoSePuedeGuardarDosVeces() {
        UUID compraId = UUID.randomUUID();
        claves.saveAndFlush(new ClaveIdempotencia("cliente-1", "clave-1", compraId, "huella-a"));

        assertThatThrownBy(() ->
                claves.saveAndFlush(new ClaveIdempotencia("cliente-1", "clave-1", compraId, "huella-b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laMismaClaveEnDosClientesDistintosNoChoca() {
        claves.saveAndFlush(new ClaveIdempotencia("cliente-1", "1", UUID.randomUUID(), "huella-a"));
        claves.saveAndFlush(new ClaveIdempotencia("cliente-2", "1", UUID.randomUUID(), "huella-b"));

        assertThat(claves.findAll()).hasSize(2);
        assertThat(claves.findById(new ClaveIdempotenciaId("cliente-1", "1")).orElseThrow().getHuellaPedido())
                .isEqualTo("huella-a");
        assertThat(claves.findById(new ClaveIdempotenciaId("cliente-2", "1")).orElseThrow().getHuellaPedido())
                .isEqualTo("huella-b");
    }

    @Test
    void laClaveDeCobroSeGeneraUnaVezYSeConserva() {
        ClaveIdempotencia nueva = claves.saveAndFlush(
                new ClaveIdempotencia("cliente-1", "clave-1", UUID.randomUUID(), "huella-a"));
        String claveCobro = nueva.getClaveCobro();

        assertThat(claveCobro).isNotBlank();
        assertThat(claves.findById(new ClaveIdempotenciaId("cliente-1", "clave-1")).orElseThrow().getClaveCobro())
                .isEqualTo(claveCobro);
    }

    @Test
    void laCompraGuardaElPagoIdCuandoSeApruebaElCobro() {
        Compra c = nuevaCompra("cliente-1");
        assertThat(compras.findById(c.getId()).orElseThrow().getPagoId()).isNull();

        c.setPagoId("pago-uuid-1");
        compras.saveAndFlush(c);

        assertThat(compras.findById(c.getId()).orElseThrow().getPagoId()).isEqualTo("pago-uuid-1");
    }
}
