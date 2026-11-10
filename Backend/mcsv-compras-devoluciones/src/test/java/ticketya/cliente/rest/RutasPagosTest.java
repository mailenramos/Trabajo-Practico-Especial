package ticketya.cliente.rest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Las rutas de Pagos toman por defecto las internas, se pueden cambiar y se validan al arrancar. */
class RutasPagosTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ClientesRestConfig.class);

    @Test
    void sinPropiedadesUsaLasRutasInternas() {
        contexto.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(RutasPagos.class)).isEqualTo(RutasPagos.internas());
        });
        assertThat(RutasPagos.internas()).isEqualTo(new RutasPagos("/internal/pagos",
                "/internal/pagos/compra/{purchaseId}", "/internal/pagos/{paymentId}/reintegros",
                "/internal/reintegros/{refundId}/reintentar", "/internal/reintegros/{refundId}"));
    }

    @Test
    void unaPropiedadReemplazaSoloEsaRuta() {
        contexto.withPropertyValues("servicios.pagos.rutas.cobro=/api/v1/payments").run(c -> {
            RutasPagos rutas = c.getBean(RutasPagos.class);
            assertThat(rutas.cobro()).isEqualTo("/api/v1/payments");
            assertThat(rutas.reintegro()).isEqualTo(RutasPagos.REINTEGRO);
        });
    }

    @Test
    void unaRutaInvalidaHaceFallarElArranque() {
        contexto.withPropertyValues("servicios.pagos.rutas.reintegro=internal/pagos").run(c ->
                assertThat(c).hasFailed());
        assertThatThrownBy(() -> new RutasPagos(" ", "/a", "/b", "/c", "/d"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cobro");
    }
}
