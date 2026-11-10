package ticketya.web;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.servicio.CancelacionService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Una compra pendiente ya cobrada (emisión fallida) cuyo pago se retoma mientras se procesa
 * la cancelación de su evento: gana quien cambia primero el estado de la compra y nunca hay
 * dos reintegros, un segundo cobro ni una compra confirmada y cerrada a la vez.
 */
class CancelacionConPagoSimultaneoTest extends PruebaDevoluciones {

    private static final String CLAVE = "clave-1";

    @Autowired
    CancelacionService cancelacionService;

    /** Pago aprobado y venta confirmada, pero la emisión falló los 3 intentos. */
    private UUID compraPendienteCobrada() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        entradas.fallarProximas(3);
        pagar(CLIENTE, id, CLAVE, "CREDIT_CARD").andExpect(status().isAccepted());
        entradas.fallarProximas(0);
        return id;
    }

    private void cancelarEvento() {
        cancelacionService.recibir(EVENTO_ID, "Lluvia", Instant.parse("2026-11-08T12:00:00Z"));
    }

    private List<Reintegro> reintegrosPorCancelacion(UUID compraId) {
        return reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO);
    }

    /**
     * En las repeticiones impares arrancan juntos (suele ganar la cancelación); en las pares la
     * cancelación arranca un poco después, cuando el pago ya tiene la compra bloqueada durante
     * la emisión, y tiene que esperar ese bloqueo. Así se cubren los dos órdenes.
     */
    @RepeatedTest(6)
    void pagoRetomadoYCancelacionALaVezDejanUnSoloResultadoConsistente(RepetitionInfo repeticion) throws Exception {
        UUID id = compraPendienteCobrada();
        entradas.setDemoraEmisionMs(200);
        long esperaCancelacion = repeticion.getCurrentRepetition() % 2 == 0 ? 80 : 0;

        List<Object> resultados = PagoTest.alMismoTiempo(
                () -> pagar(CLIENTE, id, CLAVE, "CREDIT_CARD").andReturn(),
                () -> {
                    Thread.sleep(esperaCancelacion);
                    cancelarEvento();
                    return "cancelado";
                });

        MvcResult pago = (MvcResult) resultados.get(0);
        assertThat(pago.getResponse().getStatus()).isEqualTo(200);
        Compra compra = compras.buscarConDetalle(id).orElseThrow();
        List<Reintegro> porCancelacion = reintegrosPorCancelacion(id);
        assertThat(porCancelacion).hasSize(1);
        Reintegro r = porCancelacion.get(0);
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(r.getMonto()).isEqualByComparingTo(compra.getTotal());
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(reservas.confirmaciones()).isEqualTo(1);

        if (compra.getEstado() == EstadoCompra.CONFIRMADA) {
            // Ganó el pago: la cancelación la procesó como confirmada
            assertThat(pago.getResponse().getContentAsString()).contains("\"estado\":\"CONFIRMADA\"");
            assertThat(entradas.emisiones()).isEqualTo(1);
            assertThat(compra.getDetalle()).allSatisfy(d ->
                    assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
        } else {
            // Ganó la cancelación: el pago no emitió ni confirmó
            assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
            assertThat(pago.getResponse().getContentAsString()).contains("\"estado\":\"FALLIDA\"");
            assertThat(entradas.emisiones()).isZero();
            assertThat(compra.getDetalle()).allSatisfy(d -> assertThat(d.getEntradaId()).isNull());
        }
    }

    @Test
    void siElPagoTerminaPrimeroLaCancelacionLaProcesaComoConfirmada() throws Exception {
        UUID id = compraPendienteCobrada();

        pagar(CLIENTE, id, CLAVE, "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));
        cancelarEvento();

        Compra compra = compras.buscarConDetalle(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
        assertThat(reintegrosPorCancelacion(id)).hasSize(1)
                .first().satisfies(r -> assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO));
        assertThat(compra.getDetalle()).extracting(DetalleCompra::getEntradaId).allSatisfy(e ->
                assertThat(entradas.estado(e)).isEqualTo(EstadoEntrada.DEVUELTA));
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
    }

    @Test
    void siLaCancelacionTerminaPrimeroElPagoRetomadoNoCobraNiConfirmaNiEmite() throws Exception {
        UUID id = compraPendienteCobrada();
        int llamadasDeEmision = entradas.llamadas();

        cancelarEvento();
        pagar(CLIENTE, id, CLAVE, "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"))
                .andExpect(jsonPath("$.reintegros[0].origen").value("CANCELACION_EVENTO"));

        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(reintegrosPorCancelacion(id)).hasSize(1);
        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(reservas.confirmaciones()).isEqualTo(1);
        assertThat(entradas.llamadas()).isEqualTo(llamadasDeEmision);
        assertThat(entradas.emisiones()).isZero();
    }
}
