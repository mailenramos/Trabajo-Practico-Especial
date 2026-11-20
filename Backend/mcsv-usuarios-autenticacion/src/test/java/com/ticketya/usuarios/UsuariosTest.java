package com.ticketya.usuarios;

import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UsuariosTest extends PruebaApi {

    // ---------- US-12.5 Perfil ----------

    @Test
    void consultarPerfilSinIdentidadDevuelveNoAutenticado() throws Exception {
        mvc.perform(get("/usuarios/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("FALTA_IDENTIFICACION"));
    }

    @Test
    void consultarPerfilDevuelveLosDatosSinHash() throws Exception {
        String correo = correoUnico();
        UUID id = registrarCliente(correo);

        mvc.perform(como(get("/usuarios/me"), id, "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.correo").value(correo))
                .andExpect(jsonPath("$.roles", contains("CLIENTE")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void actualizarPerfilSoloCambiaNombreYApellido() throws Exception {
        String correo = correoUnico();
        UUID id = registrarCliente(correo);

        mvc.perform(conJson(como(put("/usuarios/me"), id, "CLIENTE"), Map.of(
                        "nombre", "Ana María", "apellido", "López",
                        "correo", "otro@ticketya.test", "roles", List.of("ADMIN"), "activo", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Ana María"))
                .andExpect(jsonPath("$.apellido").value("López"))
                .andExpect(jsonPath("$.correo").value(correo))
                .andExpect(jsonPath("$.roles", contains("CLIENTE")))
                .andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    void actualizarPerfilConDatosInvalidosDevuelveValidacion() throws Exception {
        UUID id = registrarCliente(correoUnico());
        mvc.perform(conJson(como(put("/usuarios/me"), id, "CLIENTE"), Map.of("nombre", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").exists())
                .andExpect(jsonPath("$.campos.apellido").exists());
    }

    // ---------- US-12.6 Administración ----------

    @Test
    void soloUnAdministradorPuedeListarYConsultarUsuarios() throws Exception {
        UUID cliente = registrarCliente(correoUnico());

        mvc.perform(como(get("/usuarios"), cliente, "CLIENTE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESO_DENEGADO"));
        mvc.perform(como(get("/usuarios/" + cliente), cliente, "CLIENTE,ORGANIZADOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listarFiltraPorRolYPorCorreo() throws Exception {
        mvc.perform(como(get("/usuarios").param("rol", "ORGANIZADOR"), ADMIN_PRUEBA, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].roles", everyItem(hasItem("ORGANIZADOR"))))
                .andExpect(jsonPath("$.content[*].correo", hasItem("organizador@ticketya.test")));

        mvc.perform(como(get("/usuarios").param("correo", "PERSONAL@").param("size", "5"), ADMIN_PRUEBA, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.content[*].correo", contains("personal@ticketya.test")));

        mvc.perform(como(get("/usuarios").param("rol", "SUPERHEROE"), ADMIN_PRUEBA, "ADMIN"))
                .andExpect(status().isBadRequest());
        mvc.perform(como(get("/usuarios").param("size", "500"), ADMIN_PRUEBA, "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void consultarUsuarioInexistenteDevuelveNoEncontrado() throws Exception {
        mvc.perform(como(get("/usuarios/" + UUID.randomUUID()), ADMIN_PRUEBA, "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    void administradorAsignaVariosRoles() throws Exception {
        UUID id = registrarCliente(correoUnico());

        mvc.perform(conJson(como(put("/usuarios/" + id + "/roles"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("roles", List.of("ORGANIZADOR", "cliente"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("CLIENTE", "ORGANIZADOR")));

        assertThat(repositorio.findById(id).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Rol.CLIENTE, Rol.ORGANIZADOR);
    }

    @Test
    void asignarRolesValidaRolYUsuario() throws Exception {
        UUID id = registrarCliente(correoUnico());

        mvc.perform(conJson(como(put("/usuarios/" + id + "/roles"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("roles", List.of("SUPERHEROE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.roles").exists());
        mvc.perform(conJson(como(put("/usuarios/" + id + "/roles"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("roles", List.of())))
                .andExpect(status().isBadRequest());
        mvc.perform(conJson(como(put("/usuarios/" + UUID.randomUUID() + "/roles"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("roles", List.of("CLIENTE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void unClienteNoPuedeAutoasignarseRoles() throws Exception {
        UUID id = registrarCliente(correoUnico());
        mvc.perform(conJson(como(put("/usuarios/" + id + "/roles"), id, "CLIENTE"),
                        Map.of("roles", List.of("ORGANIZADOR"))))
                .andExpect(status().isForbidden());
        assertThat(repositorio.findById(id).orElseThrow().getRoles()).containsExactly(Rol.CLIENTE);
    }

    @Test
    void elUnicoAdministradorActivoNoPuedeQuitarseElRolAdmin() throws Exception {
        UUID adminInicial = repositorio.findByCorreo("admin.inicial@ticketya.test").orElseThrow().getId();
        // Se deja como único admin activo al inicial, desactivando al admin de prueba.
        mvc.perform(conJson(como(put("/usuarios/" + ADMIN_PRUEBA + "/estado"), adminInicial, "ADMIN"),
                        Map.of("activo", false)))
                .andExpect(status().isOk());
        try {
            mvc.perform(conJson(como(put("/usuarios/" + adminInicial + "/roles"), adminInicial, "ADMIN"),
                            Map.of("roles", List.of("CLIENTE"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ULTIMO_ADMINISTRADOR"));
            assertThat(repositorio.findById(adminInicial).orElseThrow().tieneRol(Rol.ADMIN)).isTrue();
        } finally {
            mvc.perform(conJson(como(put("/usuarios/" + ADMIN_PRUEBA + "/estado"), adminInicial, "ADMIN"),
                            Map.of("activo", true)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void conOtroAdministradorActivoSiSePuedeQuitarElRolAdmin() throws Exception {
        UUID id = registrarCliente(correoUnico());
        mvc.perform(conJson(como(put("/usuarios/" + id + "/roles"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("roles", List.of("ADMIN")))).andExpect(status().isOk());

        mvc.perform(conJson(como(put("/usuarios/" + id + "/roles"), id, "ADMIN"),
                        Map.of("roles", List.of("CLIENTE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("CLIENTE")));
    }

    // ---------- US-12.8 Estado ----------

    @Test
    void desactivarYReactivarUnUsuario() throws Exception {
        String correo = correoUnico();
        UUID id = registrarCliente(correo);

        mvc.perform(conJson(como(put("/usuarios/" + id + "/estado"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("activo", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
        assertThat(repositorio.findById(id)).isPresent();

        login(correo, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("USUARIO_INACTIVO"))
                .andExpect(jsonPath("$.token").doesNotExist());

        // Otros servicios siguen obteniendo el contacto de una cuenta inactiva.
        mvc.perform(get("/internal/usuarios/" + id + "/contacto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value(correo));

        mvc.perform(conJson(como(put("/usuarios/" + id + "/estado"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("activo", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));
        login(correo, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void unAdministradorNoPuedeDesactivarseASiMismo() throws Exception {
        mvc.perform(conJson(como(put("/usuarios/" + ADMIN_PRUEBA + "/estado"), ADMIN_PRUEBA, "ADMIN"),
                        Map.of("activo", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AUTODESACTIVACION"));
        assertThat(repositorio.findById(ADMIN_PRUEBA).orElseThrow().isActivo()).isTrue();
    }

    @Test
    void unNoAdministradorNoPuedeCambiarEstados() throws Exception {
        UUID id = registrarCliente(correoUnico());
        UUID otro = registrarCliente(correoUnico());
        mvc.perform(conJson(como(put("/usuarios/" + otro + "/estado"), id, "CLIENTE,ORGANIZADOR"),
                        Map.of("activo", false)))
                .andExpect(status().isForbidden());
        mvc.perform(conJson(como(put("/usuarios/" + otro + "/estado"), ADMIN_PRUEBA, "ADMIN"), Map.of()))
                .andExpect(status().isBadRequest());
    }

    // ---------- Correlación entre servicios ----------

    @Test
    void devuelveElIdentificadorDeCorrelacionRecibidoOGeneraUnoNuevo() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        mvc.perform(get("/internal/usuarios/por-correo").param("correo", "cliente@ticketya.test")
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", correlationId));

        String generado = mvc.perform(get("/internal/usuarios/por-correo").param("correo", "cliente@ticketya.test")
                        .header("X-Correlation-Id", "no-es-uuid\nlinea-falsa"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(UUID.fromString(generado)).isNotNull();
    }

    // ---------- US-12.7 Consultas internas ----------

    @Test
    void contactoDevuelveSoloElCorreo() throws Exception {
        String correo = correoUnico();
        UUID id = registrarCliente(correo);
        mvc.perform(get("/internal/usuarios/" + id + "/contacto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value(correo))
                .andExpect(jsonPath("$.nombre").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(get("/internal/usuarios/" + UUID.randomUUID() + "/contacto"))
                .andExpect(status().isNotFound());
    }

    @Test
    void porCorreoDevuelveIdentificadorYRoles() throws Exception {
        Usuario personal = repositorio.findByCorreo("personal@ticketya.test").orElseThrow();
        mvc.perform(get("/internal/usuarios/por-correo").param("correo", " Personal@TicketYa.test "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(personal.getId().toString()))
                .andExpect(jsonPath("$.roles", contains("PERSONAL_ACCESO")));
        mvc.perform(get("/internal/usuarios/por-correo").param("correo", correoUnico()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/usuarios/por-correo"))
                .andExpect(status().isBadRequest());
    }
}
