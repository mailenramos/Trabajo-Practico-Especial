package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.PruebaIntegracion;
import ticketya.modelo.DetalleCompra;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Antes de repetir una emisión incierta se consultan las entradas de la compra. */
class EmisionInciertaTest extends PruebaIntegracion {

    private static final String CLIENTE = "cliente-1";

    private void confirmadaConEntradas(UUID compraId) {
        assertThat(compras.buscarConDetalle(compraId).orElseThrow().getDetalle())
                .extracting(DetalleCompra::getEntradaId).doesNotContainNull();
    }

    @Test
    void unaEmisionSinProblemasNoConsulta() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(entradas.consultasPorCompra()).isZero();
        assertThat(entradas.llamadas()).isEqualTo(1);
    }

    @Test
    void siLaEmisionSeHizoSinRespuestaSeAsignanLasEntradasSinVolverAEmitir() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        entradas.perderProximasRespuestas(1);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(entradas.consultasPorCompra()).isEqualTo(1);
        assertThat(entradas.llamadas()).as("no se vuelve a emitir").isEqualTo(1);
        assertThat(entradas.emisiones()).isEqualTo(1);
        confirmadaConEntradas(compraId);
    }

    @Test
    void siLaConsultaDevuelveUnaListaVaciaSeEmiteComoSiempre() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        // El primer intento falla antes de emitir: Entradas no tiene entradas de la compra
        entradas.fallarProximas(1);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(entradas.consultasPorCompra()).isEqualTo(1);
        assertThat(entradas.llamadas()).isEqualTo(2);
        assertThat(entradas.emisiones()).isEqualTo(1);
        confirmadaConEntradas(compraId);
    }

    @Test
    void unPagoRetomadoConsultaAntesDeEmitirYUsaLasEntradasYaEmitidas() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        // La emisión se hace sin que llegue la respuesta y las consultas siguientes no responden:
        // el pago queda a medias
        entradas.perderProximasRespuestas(1);
        entradas.setCaido(true);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isAccepted());
        int emisionesPedidas = entradas.llamadas();
        assertThat(emisionesPedidas).isEqualTo(1);
        entradas.setCaido(false);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(entradas.llamadas()).as("el pedido retomado no emite").isEqualTo(emisionesPedidas);
        assertThat(entradas.emisiones()).isEqualTo(1);
        confirmadaConEntradas(compraId);
    }
}
