package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /reportes/eventos/{eventoId}/ventas, con compras hechas por el flujo real. */
class ReporteVentasTest extends PruebaReportes {

    @Test
    void informaLasVentasConfirmadasPorSectorConLosPreciosHistoricos() throws Exception {
        compraConfirmada("cliente-1");
        // Platea sube de precio: la compra siguiente se hace al precio nuevo y la anterior no cambia
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba", Instant.parse("2026-12-01T21:00:00Z"),
                "publicado", List.of(
                new Sector(PLATEA, "Platea", new BigDecimal("20000.00")),
                new Sector(CAMPO, "Campo", new BigDecimal("8000.50"))), "RECITAL", ORGANIZADOR));
        compraConfirmada("cliente-2");

        comoOrganizador("ventas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.comprasConfirmadas").value(2))
                .andExpect(jsonPath("$.totales.entradasVendidas").value(8))
                .andExpect(jsonPath("$.totales.importeVendido").value(102002.00))
                .andExpect(jsonPath("$.sectores[0].sectorId").value(PLATEA))
                .andExpect(jsonPath("$.sectores[0].sectorNombre").value("Platea"))
                .andExpect(jsonPath("$.sectores[0].entradasVendidas").value(4))
                .andExpect(jsonPath("$.sectores[0].importeVendido").value(70000.00))
                .andExpect(jsonPath("$.sectores[1].sectorId").value(CAMPO))
                .andExpect(jsonPath("$.sectores[1].entradasVendidas").value(4))
                .andExpect(jsonPath("$.sectores[1].importeVendido").value(32002.00))
                .andExpect(jsonPath("$.comprasConEmisionIncompleta.cantidad").value(0))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(false));
    }

    @Test
    void unEventoSinVentasMuestraSusSectoresEnCero() throws Exception {
        comoAdmin("ventas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.comprasConfirmadas").value(0))
                .andExpect(jsonPath("$.totales.entradasVendidas").value(0))
                .andExpect(jsonPath("$.totales.importeVendido").value(0.00))
                .andExpect(jsonPath("$.sectores.length()").value(2))
                .andExpect(jsonPath("$.sectores[0].entradasVendidas").value(0))
                .andExpect(jsonPath("$.sectores[1].importeVendido").value(0.00));
    }

    @Test
    void unaCompraCobradaConLaEmisionSinTerminarSeInformaAparte() throws Exception {
        compraConfirmada("cliente-1");
        UUID incompleta = compraPendiente("cliente-2");
        entradas.fallarProximas(100);
        pagar("cliente-2", incompleta, "clave-incompleta", "CREDIT_CARD").andExpect(status().isAccepted());
        Compra c = compras.findById(incompleta).orElseThrow();
        assertThat(c.getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(c.getPagoId()).isNotNull();
        // Una compra iniciada sin pagar no es una operación pendiente ni una venta
        compraPendiente("cliente-3");

        comoOrganizador("ventas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.comprasConfirmadas").value(1))
                .andExpect(jsonPath("$.totales.entradasVendidas").value(4))
                .andExpect(jsonPath("$.totales.importeVendido").value(46001.00))
                .andExpect(jsonPath("$.comprasConEmisionIncompleta.cantidad").value(1))
                .andExpect(jsonPath("$.comprasConEmisionIncompleta.importe").value(46001.00))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(true));
    }

    @Test
    void unaCompraPendienteSinPagoNoMarcaOperacionesPendientes() throws Exception {
        compraConfirmada("cliente-1");
        compraPendiente("cliente-2");

        comoOrganizador("ventas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comprasConEmisionIncompleta.cantidad").value(0))
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(false));
    }

    @Test
    void unaEntradaDevueltaSigueContandoComoVendida() throws Exception {
        UUID id = compraConfirmada("cliente-1");
        devolucion(id, detalles(id, PLATEA).get(0), EstadoReintegro.EXITOSO);

        comoOrganizador("ventas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.entradasVendidas").value(4))
                .andExpect(jsonPath("$.sectores[0].entradasVendidas").value(2))
                .andExpect(jsonPath("$.totales.importeVendido").value(46001.00));
    }
}
