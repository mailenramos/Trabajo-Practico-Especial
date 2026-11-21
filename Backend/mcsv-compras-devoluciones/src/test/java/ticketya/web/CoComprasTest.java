package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.PruebaIntegracion;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /internal/eventos/{eventoId}/co-compras. */
class CoComprasTest extends PruebaIntegracion {

    private void compra(String clienteId, String eventoId, EstadoCompra estado) {
        Compra c = new Compra();
        c.setClienteId(clienteId);
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId(eventoId);
        c.setEventoNombre("Evento " + eventoId);
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setTotal(new BigDecimal("1000.00"));
        c.setEstado(estado);
        compras.saveAndFlush(c);
    }

    @Test
    void devuelveLasCoComprasOrdenadasPorCantidad() throws Exception {
        compra("c1", "A", EstadoCompra.CONFIRMADA);
        compra("c2", "A", EstadoCompra.CONFIRMADA);
        compra("c1", "B", EstadoCompra.CONFIRMADA);
        compra("c2", "B", EstadoCompra.CONFIRMADA);
        compra("c2", "C", EstadoCompra.CONFIRMADA);
        compra("c2", "D", EstadoCompra.PENDIENTE);

        consultarInterno("/internal/eventos/{id}/co-compras", "A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventoId").value("A"))
                .andExpect(jsonPath("$.coCompras", hasSize(2)))
                .andExpect(jsonPath("$.coCompras[0].eventoId").value("B"))
                .andExpect(jsonPath("$.coCompras[0].cantidad").value(2))
                .andExpect(jsonPath("$.coCompras[1].eventoId").value("C"))
                .andExpect(jsonPath("$.coCompras[1].cantidad").value(1));
    }

    @Test
    void sinLimiteDevuelveDiezYUnLimiteMayorA20SeTrataComo20() throws Exception {
        compra("c1", "A", EstadoCompra.CONFIRMADA);
        for (int i = 0; i < 25; i++) {
            compra("c1", "otro-" + (char) ('a' + i), EstadoCompra.CONFIRMADA);
        }

        consultarInterno("/internal/eventos/{id}/co-compras", "A")
                .andExpect(status().isOk()).andExpect(jsonPath("$.coCompras", hasSize(10)));
        consultarInterno("/internal/eventos/{id}/co-compras?limite=3", "A")
                .andExpect(status().isOk()).andExpect(jsonPath("$.coCompras", hasSize(3)));
        consultarInterno("/internal/eventos/{id}/co-compras?limite=500", "A")
                .andExpect(status().isOk()).andExpect(jsonPath("$.coCompras", hasSize(20)));
    }

    @Test
    void sinResultadosRespondeListaVaciaY200() throws Exception {
        consultarInterno("/internal/eventos/{id}/co-compras", "inexistente")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventoId").value("inexistente"))
                .andExpect(jsonPath("$.coCompras", hasSize(0)));
    }

    @Test
    void unLimiteInvalidoResponde400() throws Exception {
        consultarInterno("/internal/eventos/{id}/co-compras?limite=0", "A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PARAMETRO_INVALIDO"));
        consultarInterno("/internal/eventos/{id}/co-compras?limite=abc", "A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PARAMETRO_INVALIDO"));
    }

    @Test
    void sinTokenOConTokenIncorrectoResponde403() throws Exception {
        mvc.perform(get("/internal/eventos/{id}/co-compras", "A"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_AUTORIZADO"));
        mvc.perform(get("/internal/eventos/{id}/co-compras", "A")
                        .header(AutorizacionDeServicioFilter.ENCABEZADO, "otro"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_AUTORIZADO"));
    }
}
