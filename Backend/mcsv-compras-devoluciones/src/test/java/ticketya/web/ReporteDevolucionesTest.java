package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.modelo.EstadoReintegro;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /reportes/eventos/{eventoId}/devoluciones. */
class ReporteDevolucionesTest extends PruebaReportes {

    @Test
    void informaDevueltasPorOrigenNetasEImportesPorSector() throws Exception {
        // Compra 1: una entrada de Platea devuelta por el cliente y otra de Campo con la devolución pendiente
        UUID compra1 = compraConfirmada("cliente-1");
        devolucion(compra1, detalles(compra1, PLATEA).get(0), EstadoReintegro.EXITOSO);
        solicitudPendiente(compra1, detalles(compra1, CAMPO).get(0));
        // Compra 2: el evento se canceló y el reintegro de sus 4 entradas salió bien
        UUID compra2 = compraConfirmada("cliente-2");
        cancelacion(compra2, EstadoReintegro.EXITOSO, todas(compra2));

        comoOrganizador("devoluciones")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.entradasVendidas").value(8))
                .andExpect(jsonPath("$.totales.entradasDevueltas").value(5))
                .andExpect(jsonPath("$.totales.devueltasPorDevolucion").value(1))
                .andExpect(jsonPath("$.totales.devueltasPorCancelacion").value(4))
                .andExpect(jsonPath("$.totales.entradasNetas").value(3))
                .andExpect(jsonPath("$.totales.importeDevuelto").value(61001.00))
                .andExpect(jsonPath("$.totales.importeDevueltoPorDevolucion").value(15000.00))
                .andExpect(jsonPath("$.totales.importeDevueltoPorCancelacion").value(46001.00))
                .andExpect(jsonPath("$.sectores[0].sectorId").value(PLATEA))
                .andExpect(jsonPath("$.sectores[0].entradasVendidas").value(4))
                .andExpect(jsonPath("$.sectores[0].devueltasPorDevolucion").value(1))
                .andExpect(jsonPath("$.sectores[0].devueltasPorCancelacion").value(2))
                .andExpect(jsonPath("$.sectores[0].entradasDevueltas").value(3))
                .andExpect(jsonPath("$.sectores[0].entradasNetas").value(1))
                .andExpect(jsonPath("$.sectores[0].importeDevuelto").value(45000.00))
                .andExpect(jsonPath("$.sectores[1].sectorId").value(CAMPO))
                .andExpect(jsonPath("$.sectores[1].devueltasPorDevolucion").value(0))
                .andExpect(jsonPath("$.sectores[1].devueltasPorCancelacion").value(2))
                .andExpect(jsonPath("$.sectores[1].entradasNetas").value(2))
                .andExpect(jsonPath("$.sectores[1].importeDevuelto").value(16001.00))
                // La devolución pendiente va aparte y no resta de las netas
                .andExpect(jsonPath("$.devolucionesEnCurso.cantidad").value(1))
                .andExpect(jsonPath("$.devolucionesEnCurso.importe").value(8000.50))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(true));
    }

    @Test
    void unaDevolucionAprobadaConElReintegroPendienteEstaEnCursoYNoResta() throws Exception {
        UUID compra = compraConfirmada("cliente-1");
        devolucion(compra, detalles(compra, PLATEA).get(0), EstadoReintegro.PENDIENTE);

        comoOrganizador("devoluciones")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.entradasDevueltas").value(0))
                .andExpect(jsonPath("$.totales.entradasNetas").value(4))
                .andExpect(jsonPath("$.devolucionesEnCurso.cantidad").value(1))
                .andExpect(jsonPath("$.devolucionesEnCurso.importe").value(15000.00))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(true));
    }

    @Test
    void lasEntradasDeUnaCancelacionConReintegroPendienteNoCuentanComoDevueltas() throws Exception {
        UUID compra = compraConfirmada("cliente-1");
        cancelacion(compra, EstadoReintegro.PENDIENTE, todas(compra));

        comoAdmin("devoluciones")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.devueltasPorCancelacion").value(0))
                .andExpect(jsonPath("$.totales.entradasNetas").value(4))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(true));
    }

    @Test
    void sinDevolucionesTodoEstaEnCero() throws Exception {
        compraConfirmada("cliente-1");

        comoOrganizador("devoluciones")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.entradasVendidas").value(4))
                .andExpect(jsonPath("$.totales.entradasDevueltas").value(0))
                .andExpect(jsonPath("$.totales.entradasNetas").value(4))
                .andExpect(jsonPath("$.totales.importeDevuelto").value(0.00))
                .andExpect(jsonPath("$.devolucionesEnCurso.cantidad").value(0))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(false));
    }
}
