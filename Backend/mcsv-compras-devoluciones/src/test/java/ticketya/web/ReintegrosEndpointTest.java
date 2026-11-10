package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ticketya.PruebaIntegracion;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.servicio.ReintegroService;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /reintegros y POST /reintegros/{id}/reintento (solo administrador). */
class ReintegrosEndpointTest extends PruebaIntegracion {

    @Autowired
    ReintegroService reintegroService;

    /** Reintegro pedido sobre una compra pagada, con el resultado que dé el doble de Pagos. */
    private Reintegro reintegroPedido() throws Exception {
        UUID compraId = compraPendiente("cliente-1");
        pagar("cliente-1", compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());
        Compra compra = compras.findById(compraId).orElseThrow();
        Reintegro r = reintegroService.registrar(compraId, UUID.randomUUID(), new BigDecimal("15000.00"),
                "Reintegro de prueba", OrigenReintegro.VENTA_RECHAZADA);
        return reintegroService.pedir(r, compra.getPagoId());
    }

    @Test
    void elAdministradorListaLosReintegrosFallidos() throws Exception {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        Reintegro fallido = reintegroPedido();

        mvc.perform(get("/reintegros").param("estado", "FALLIDO")
                        .header("X-User-Id", "admin-1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(fallido.getId().toString()))
                .andExpect(jsonPath("$[0].estado").value("FALLIDO"))
                .andExpect(jsonPath("$[0].monto").value(15000.00))
                .andExpect(jsonPath("$[0].origen").value("VENTA_RECHAZADA"))
                .andExpect(jsonPath("$[0].intentos").value(1))
                .andExpect(jsonPath("$[0].ultimoError").isNotEmpty());

        mvc.perform(get("/reintegros").param("estado", "EXITOSO")
                        .header("X-User-Id", "admin-1").header("X-User-Role", "ADMIN"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reintentarUnFallidoQueSaleBienDa200() throws Exception {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        Reintegro fallido = reintegroPedido();

        mvc.perform(post("/reintegros/{id}/reintento", fallido.getId())
                        .header("X-User-Id", "admin-1").header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EXITOSO"))
                .andExpect(jsonPath("$.intentos").value(2));

        assertThat(reintegros.findById(fallido.getId()).orElseThrow().getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void siSigueFallandoDa202() throws Exception {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        pagos.setResultadoReintento(EstadoReintegroPagos.FALLIDO);
        Reintegro fallido = reintegroPedido();

        mvc.perform(post("/reintegros/{id}/reintento", fallido.getId())
                        .header("X-User-Id", "admin-1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("FALLIDO"));
    }

    @Test
    void unUsuarioQueNoEsAdministradorRecibe403() throws Exception {
        Reintegro r = reintegroPedido();

        mvc.perform(get("/reintegros").header("X-User-Id", "cliente-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));
        mvc.perform(post("/reintegros/{id}/reintento", r.getId()).header("X-User-Id", "cliente-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));
    }

    @Test
    void reintentarUnoInexistenteDa404() throws Exception {
        mvc.perform(post("/reintegros/{id}/reintento", UUID.randomUUID())
                        .header("X-User-Id", "admin-1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("REINTEGRO_NO_ENCONTRADO"));
    }
}
