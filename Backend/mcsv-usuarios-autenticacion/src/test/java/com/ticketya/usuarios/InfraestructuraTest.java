package com.ticketya.usuarios;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** X-Service-Token en /internal/** y /actuator/health para Docker. */
@TestPropertySource(properties = "usuarios.seguridad.servicio-token=token-de-servicio-de-prueba")
class InfraestructuraTest extends PruebaApi {

    @Test
    void rutaInternaSinTokenOConTokenIncorrecto_responde401() throws Exception {
        mvc.perform(get("/internal/usuarios/por-correo").param("correo", "cliente@ticketya.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_AUTENTICADO"));
        mvc.perform(get("/internal/usuarios/por-correo").param("correo", "cliente@ticketya.test")
                        .header("X-Service-Token", "otro"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rutaInternaConToken_respondeNormalmente() throws Exception {
        mvc.perform(get("/internal/usuarios/por-correo").param("correo", "cliente@ticketya.test")
                        .header("X-Service-Token", "token-de-servicio-de-prueba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("CLIENTE"));
    }

    @Test
    void rutasPublicasNoExigenTokenDeServicio() throws Exception {
        mvc.perform(get("/usuarios/me").header("X-User-Id", ADMIN_PRUEBA.toString()).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void healthEstaExpuestoSinDetalles() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
