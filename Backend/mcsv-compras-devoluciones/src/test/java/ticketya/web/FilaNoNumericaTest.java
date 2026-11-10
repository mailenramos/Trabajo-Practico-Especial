package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.PruebaIntegracion;
import ticketya.modelo.EstadoCompra;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Una fila que no es un número, recibida de otro servicio, es un error de contrato. */
class FilaNoNumericaTest extends PruebaIntegracion {

    // 4.2
    @Test
    void unaReservaConFilaNoNumericaDa502ContratoInvalidoSinRegistrarLaCompra() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        reservas.setFilaNoNumerica(true);

        iniciarCompra("cliente-1", reservaId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("CONTRATO_INVALIDO"))
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("Reservas")));

        assertThat(compras.count()).isZero();
    }

    // 4.3
    @Test
    void unaEmisionConFilaNoNumericaCuentaComoFallidaYLaCompraQuedaPendiente() throws Exception {
        UUID id = compraPendiente("cliente-1");
        entradas.setFilaNoNumerica(true);

        pagar("cliente-1", id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.lugares[0].entradaId").doesNotExist());

        assertThat(entradas.llamadas()).isEqualTo(3);
        var compra = compras.buscarConDetalle(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(compra.getPagoId()).isNotNull();
        assertThat(compra.getDetalle()).allSatisfy(d -> assertThat(d.getEntradaId()).isNull());
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
    }
}
