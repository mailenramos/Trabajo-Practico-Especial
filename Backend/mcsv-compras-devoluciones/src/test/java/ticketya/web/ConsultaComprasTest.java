package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.PruebaIntegracion;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.cliente.PagosCliente.EstadoPago;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C-6 (consultar las compras propias) y P-1 (aislamiento entre clientes). */
class ConsultaComprasTest extends PruebaIntegracion {

    @Test
    void elClienteVeSoloSusComprasConTodoElDetalle() throws Exception {
        UUID pagada = compraPendiente("cliente-1");
        UUID pendiente = compraPendiente("cliente-1");
        compraPendiente("cliente-2");
        pagar("cliente-1", pagada, "clave-1", "BANK_TRANSFER").andExpect(status().isOk());

        consultar("cliente-1", "/compras")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(pagada.toString(), pendiente.toString())));

        consultar("cliente-1", "/compras/{id}", pagada)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.evento.nombre").value("Recital de prueba"))
                .andExpect(jsonPath("$.evento.fechaInicio").value("2026-12-01T21:00:00Z"))
                .andExpect(jsonPath("$.lugares.length()").value(4))
                .andExpect(jsonPath("$.lugares[0].sectorNombre").value("Platea"))
                .andExpect(jsonPath("$.lugares[0].fila").value(10))
                .andExpect(jsonPath("$.lugares[0].numero").value(1))
                .andExpect(jsonPath("$.lugares[0].precioUnitario").value(15000.00))
                .andExpect(jsonPath("$.lugares[0].entradaId").isNotEmpty())
                .andExpect(jsonPath("$.total").value(46001.00))
                .andExpect(jsonPath("$.medioDePago").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.fecha").exists());
    }

    @Test
    void unClienteSinComprasRecibeUnaListaVacia() throws Exception {
        compraPendiente("cliente-1");

        consultar("cliente-9", "/compras")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void losPreciosSonLosVigentesAlMomentoDeLaCompra() throws Exception {
        UUID id = compraPendiente("cliente-1");
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba", Instant.parse("2026-12-01T21:00:00Z"),
                "publicado", List.of(
                new Sector(PLATEA, "Platea", new BigDecimal("99999.00")),
                new Sector(CAMPO, "Campo", new BigDecimal("99999.00")))));

        consultar("cliente-1", "/compras/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lugares[0].precioUnitario").value(15000.00))
                .andExpect(jsonPath("$.total").value(46001.00));
    }

    @Test
    void unaCompraFallidaFiguraConEseEstadoYSinEntradas() throws Exception {
        UUID id = compraPendiente("cliente-1");
        pagos.setDecision(EstadoPago.RECHAZADO);
        pagar("cliente-1", id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        consultar("cliente-1", "/compras/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"))
                .andExpect(jsonPath("$.lugares[*].entradaId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void consultarLaCompraDeOtroClienteDaAccesoDenegado() throws Exception {
        UUID id = compraPendiente("cliente-1");

        consultar("cliente-2", "/compras/{id}", id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESO_DENEGADO"))
                .andExpect(jsonPath("$.lugares").doesNotExist());
    }

    @Test
    void consultarUnaCompraInexistenteDa404() throws Exception {
        consultar("cliente-1", "/compras/{id}", UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COMPRA_NO_ENCONTRADA"));
    }

    @Test
    void unIdConFormatoInvalidoDa400() throws Exception {
        consultar("cliente-1", "/compras/{id}", "no-es-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PARAMETRO_INVALIDO"));
    }
}
