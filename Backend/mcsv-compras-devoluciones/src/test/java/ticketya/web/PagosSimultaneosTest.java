package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import ticketya.PruebaIntegracion;
import ticketya.modelo.EstadoCompra;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dos pagos simultáneos de la misma reserva: solo uno prospera. */
class PagosSimultaneosTest extends PruebaIntegracion {

    @Test
    void dosPagosSimultaneosDeLaMismaCompraConClavesDistintasCobranUnaSolaVez() throws Exception {
        UUID id = compraPendiente("cliente-1");
        pagos.setDemoraMs(300);

        List<MvcResult> resultados = PagoTest.alMismoTiempo(
                () -> pagar("cliente-1", id, "clave-a", "CREDIT_CARD").andReturn(),
                () -> pagar("cliente-1", id, "clave-b", "CREDIT_CARD").andReturn());

        assertThat(resultados).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("\"estado\":\"CONFIRMADA\"");
        });
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
        assertThat(reservas.confirmaciones()).isEqualTo(1);
        assertThat(entradas.emisiones()).isEqualTo(1);
        assertThat(entradas.llamadas()).isEqualTo(1);
        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
    }

    @Test
    void dosComprasSimultaneasDeLaMismaReservaRegistranUnaYTodosRecibenEsa() throws Exception {
        String reservaId = reservaVigente("cliente-1");

        List<MvcResult> resultados = PagoTest.alMismoTiempo(
                () -> iniciarCompra("cliente-1", reservaId).andReturn(),
                () -> iniciarCompra("cliente-1", reservaId).andReturn(),
                () -> iniciarCompra("cliente-1", reservaId).andReturn());

        assertThat(compras.count()).isEqualTo(1);
        String id = compras.findByReservaId(reservaId).orElseThrow().getId().toString();
        assertThat(resultados).extracting(r -> r.getResponse().getStatus()).containsOnly(201, 200);
        assertThat(resultados).allSatisfy(r ->
                assertThat(r.getResponse().getContentAsString()).contains("\"id\":\"" + id + "\""));
    }
}
