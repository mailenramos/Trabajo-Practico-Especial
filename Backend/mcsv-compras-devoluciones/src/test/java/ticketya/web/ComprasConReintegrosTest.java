package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.modelo.DetalleCompra;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La compra muestra sus reintegros. */
class ComprasConReintegrosTest extends PruebaDevoluciones {

    @Test
    void unaCompraConUnaEntradaDevueltaMuestraSuReintegro() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        UUID compraId = detalle.get(0).getCompra().getId();
        UUID devolucion = devolucionPendiente(CLIENTE, detalle.get(0).getEntradaId());
        aprobar(ADMIN, "ADMIN", devolucion).andExpect(status().isOk());

        consultar(CLIENTE, "/compras/{id}", compraId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.reintegros.length()").value(1))
                .andExpect(jsonPath("$.reintegros[0].monto").value(15000.00))
                .andExpect(jsonPath("$.reintegros[0].origen").value("DEVOLUCION"))
                .andExpect(jsonPath("$.reintegros[0].estado").value("EXITOSO"));

        consultar(CLIENTE, "/compras")
                .andExpect(jsonPath("$[0].reintegros.length()").value(1));
    }

    @Test
    void unaCompraFallidaPorVentaRechazadaMuestraElReintegroPorElTotal() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        reservas.rechazarConfirmacion("RESERVA_VENCIDA");

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reintegros[0].origen").value("VENTA_RECHAZADA"));

        consultar(CLIENTE, "/compras/{id}", compraId)
                .andExpect(jsonPath("$.estado").value("FALLIDA"))
                .andExpect(jsonPath("$.reintegros.length()").value(1))
                .andExpect(jsonPath("$.reintegros[0].monto").value(46001.00))
                .andExpect(jsonPath("$.reintegros[0].origen").value("VENTA_RECHAZADA"))
                .andExpect(jsonPath("$.reintegros[0].estado").value("EXITOSO"));
    }

    @Test
    void unaCompraSinReintegrosSeMuestraSinReintegros() throws Exception {
        UUID compraId = entradasConfirmadas(CLIENTE).get(0).getCompra().getId();

        consultar(CLIENTE, "/compras/{id}", compraId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reintegros.length()").value(0));
    }
}
