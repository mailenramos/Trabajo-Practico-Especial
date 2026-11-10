package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-3 y P-1: consultar las solicitudes de devolución. */
class ConsultaDevolucionesTest extends PruebaDevoluciones {

    @Test
    void elClienteVeSoloLasSuyasConElEstadoDeLaSolicitudYElDelReintegroPorSeparado() throws Exception {
        var propias = entradasConfirmadas(CLIENTE);
        UUID aprobada = devolucionPendiente(CLIENTE, propias.get(0).getEntradaId());
        UUID pendiente = devolucionPendiente(CLIENTE, propias.get(1).getEntradaId());
        devolucionPendiente("cliente-2", unaEntradaDePlatea("cliente-2"));
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        aprobar(ADMIN, "ADMIN", aprobada);

        consultarComo(CLIENTE, "CLIENTE", "/devoluciones")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(aprobada.toString(), pendiente.toString())));

        // La solicitud aprobada con su reintegro fallido: dos estados independientes
        consultarComo(CLIENTE, "CLIENTE", "/devoluciones/{id}", aprobada)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.reintegro.estado").value("FALLIDO"))
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").value("REINTEGRO"));

        consultarComo(CLIENTE, "CLIENTE", "/devoluciones/{id}", pendiente)
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.reintegro").doesNotExist());
    }

    @Test
    void unaSolicitudRechazadaMuestraElMotivo() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        rechazar(ADMIN, "ADMIN", id, "La entrada ya fue usada");

        consultarComo(CLIENTE, "CLIENTE", "/devoluciones/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.motivoRechazo").value("La entrada ya fue usada"));
    }

    @Test
    void laSolicitudDeOtroClienteDaAccesoDenegado() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        consultarComo("cliente-2", "CLIENTE", "/devoluciones/{id}", id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESO_DENEGADO"))
                .andExpect(jsonPath("$.entradaId").doesNotExist());
    }

    @Test
    void elAdministradorVeLasPendientesDeTodosLosClientes() throws Exception {
        UUID deUno = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        UUID deOtro = devolucionPendiente("cliente-2", unaEntradaDePlatea("cliente-2"));
        UUID rechazada = devolucionPendiente("cliente-3", unaEntradaDePlatea("cliente-3"));
        rechazar(ADMIN, "ADMIN", rechazada, "no");

        mvc.perform(get("/devoluciones")
                        .param("estado", "PENDIENTE").header("X-User-Id", ADMIN).header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(deUno.toString(), deOtro.toString())));

        consultarComo(ADMIN, "ADMIN", "/devoluciones/{id}", deOtro).andExpect(status().isOk());
    }

    @Test
    void unaSolicitudInexistenteDa404() throws Exception {
        consultarComo(CLIENTE, "CLIENTE", "/devoluciones/{id}", UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVOLUCION_NO_ENCONTRADA"));
    }
}
