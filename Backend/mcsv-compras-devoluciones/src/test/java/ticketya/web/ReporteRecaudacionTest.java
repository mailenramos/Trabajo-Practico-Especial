package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /reportes/eventos/{eventoId}/recaudacion. Cada compra estándar: Platea 30000.00 y Campo 16001.00. */
class ReporteRecaudacionTest extends PruebaReportes {

    @Test
    void laNetaDescuentaSoloLosReintegrosExitososDeComprasConfirmadas() throws Exception {
        UUID compra1 = compraConfirmada("cliente-1");
        compraConfirmada("cliente-2");
        devolucion(compra1, detalles(compra1, PLATEA).get(0), EstadoReintegro.EXITOSO);

        comoOrganizador("recaudacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.recaudacionBruta").value(92002.00))
                .andExpect(jsonPath("$.totales.reintegrosExitosos").value(15000.00))
                .andExpect(jsonPath("$.totales.recaudacionNeta").value(77002.00))
                .andExpect(jsonPath("$.sectores[0].sectorId").value(PLATEA))
                .andExpect(jsonPath("$.sectores[0].recaudacionBruta").value(60000.00))
                .andExpect(jsonPath("$.sectores[0].reintegrosExitosos").value(15000.00))
                .andExpect(jsonPath("$.sectores[0].recaudacionNeta").value(45000.00))
                .andExpect(jsonPath("$.sectores[1].sectorId").value(CAMPO))
                .andExpect(jsonPath("$.sectores[1].recaudacionBruta").value(32002.00))
                .andExpect(jsonPath("$.sectores[1].reintegrosExitosos").value(0.00))
                .andExpect(jsonPath("$.sectores[1].recaudacionNeta").value(32002.00))
                .andExpect(jsonPath("$.pendientesDeReconciliacion.reintegrosPendientes.cantidad").value(0))
                .andExpect(jsonPath("$.pendientesDeReconciliacion.reintegrosFallidos.cantidad").value(0))
                .andExpect(jsonPath("$.comprasNoConfirmadas.compras").value(0))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(false));
    }

    @Test
    void elReintegroPorCancelacionSeDescuentaEnCadaSectorSegunSusEntradas() throws Exception {
        UUID compra = compraConfirmada("cliente-1");
        cancelacion(compra, EstadoReintegro.EXITOSO, todas(compra));

        comoAdmin("recaudacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.recaudacionBruta").value(46001.00))
                .andExpect(jsonPath("$.totales.reintegrosExitosos").value(46001.00))
                .andExpect(jsonPath("$.totales.recaudacionNeta").value(0.00))
                .andExpect(jsonPath("$.sectores[0].reintegrosExitosos").value(30000.00))
                .andExpect(jsonPath("$.sectores[1].reintegrosExitosos").value(16001.00));
    }

    @Test
    void losReintegrosPendientesYFallidosNoSeDescuentanYVanAparte() throws Exception {
        UUID compra = compraConfirmada("cliente-1");
        devolucion(compra, detalles(compra, PLATEA).get(0), EstadoReintegro.FALLIDO);
        devolucion(compra, detalles(compra, CAMPO).get(0), EstadoReintegro.PENDIENTE);

        comoOrganizador("recaudacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.recaudacionBruta").value(46001.00))
                .andExpect(jsonPath("$.totales.reintegrosExitosos").value(0.00))
                .andExpect(jsonPath("$.totales.recaudacionNeta").value(46001.00))
                .andExpect(jsonPath("$.pendientesDeReconciliacion.reintegrosFallidos.cantidad").value(1))
                .andExpect(jsonPath("$.pendientesDeReconciliacion.reintegrosFallidos.importe").value(15000.00))
                .andExpect(jsonPath("$.pendientesDeReconciliacion.reintegrosPendientes.cantidad").value(1))
                .andExpect(jsonPath("$.pendientesDeReconciliacion.reintegrosPendientes.importe").value(8000.50))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(true));
    }

    @Test
    void elCobroYElReintegroDeUnaVentaRechazadaVanAparte() throws Exception {
        compraConfirmada("cliente-1");
        UUID rechazada = compraPendiente("cliente-2");
        reservas.rechazarConfirmacion("RESERVA_VENCIDA");
        pagar("cliente-2", rechazada, "clave-rechazada", "CREDIT_CARD").andExpect(status().isOk());
        assertThat(compras.findById(rechazada).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(reintegros.findByCompraId(rechazada)).singleElement().satisfies(r -> {
            assertThat(r.getOrigen()).isEqualTo(OrigenReintegro.VENTA_RECHAZADA);
            assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        });

        comoOrganizador("recaudacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.recaudacionBruta").value(46001.00))
                .andExpect(jsonPath("$.totales.reintegrosExitosos").value(0.00))
                .andExpect(jsonPath("$.totales.recaudacionNeta").value(46001.00))
                .andExpect(jsonPath("$.comprasNoConfirmadas.compras").value(1))
                .andExpect(jsonPath("$.comprasNoConfirmadas.cobrado").value(46001.00))
                .andExpect(jsonPath("$.comprasNoConfirmadas.reintegrado").value(46001.00))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(false));
    }
}
