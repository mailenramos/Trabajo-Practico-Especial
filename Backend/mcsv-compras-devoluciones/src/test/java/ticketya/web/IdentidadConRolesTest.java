package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import ticketya.modelo.EstadoSolicitud;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Identidad del Gateway con X-User-Roles (varios roles): titularidad y permisos de administrador. */
class IdentidadConRolesTest extends PruebaDevoluciones {

    private ResultActions getConRoles(String usuario, String roles, String ruta, Object... variables) throws Exception {
        return mvc.perform(get(ruta, variables).header("X-User-Id", usuario).header("X-User-Roles", roles));
    }

    private ResultActions aprobarConRoles(String usuario, String roles, UUID devolucionId) throws Exception {
        return mvc.perform(post("/devoluciones/{id}/aprobacion", devolucionId)
                .header("X-User-Id", usuario).header("X-User-Roles", roles));
    }

    @Test
    void elTitularConVariosRolesConsultaSuCompra() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        getConRoles(CLIENTE, "CLIENTE,PERSONAL_ACCESO", "/compras/{id}", compraId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(compraId.toString()));
        getConRoles(CLIENTE, "CLIENTE,PERSONAL_ACCESO", "/compras")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void otroUsuarioSinRolDeAdministradorSigueSinPoderVerLaCompra() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        getConRoles("otro", "CLIENTE,PERSONAL_ACCESO", "/compras/{id}", compraId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESO_DENEGADO"));
    }

    @Test
    void unAdministradorConVariosRolesApruebaYVeTodo() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        aprobarConRoles(ADMIN, "cliente, admin", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"));
        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.APROBADA);

        getConRoles(ADMIN, "CLIENTE,ADMIN", "/devoluciones").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        getConRoles(ADMIN, "CLIENTE,ADMIN", "/devoluciones/{id}", id).andExpect(status().isOk());
        getConRoles(ADMIN, "CLIENTE,ADMIN", "/reintegros").andExpect(status().isOk());
    }

    @Test
    void unUsuarioSinElRolDeAdministradorRecibe403EnUnaOperacionDeAdministrador() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        aprobarConRoles(ADMIN, "CLIENTE,PERSONAL_ACCESO", id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));
        getConRoles(ADMIN, "CLIENTE", "/reintegros")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
    }

    @Test
    void siLleganLosDosEncabezadosMandaElNuevo() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        mvc.perform(post("/devoluciones/{id}/aprobacion", id)
                        .header("X-User-Id", ADMIN).header("X-User-Roles", "CLIENTE").header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));
    }

    @Test
    void elEncabezadoAnteriorSigueFuncionando() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        aprobar(ADMIN, "ADMIN", id).andExpect(status().isOk());
    }

    @Test
    void sinXUserIdResponde401AunqueTengaRoles() throws Exception {
        mvc.perform(get("/compras").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("USUARIO_NO_IDENTIFICADO"));
    }
}
