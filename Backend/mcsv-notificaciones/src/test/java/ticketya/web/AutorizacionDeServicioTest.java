package ticketya.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ticketya.config.NotificacionesProperties;
import ticketya.repositorio.NotificacionRepository;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutorizacionDeServicioTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    NotificacionRepository repositorio;

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    String avisoValido() {
        return NotificacionControllerTest.aviso("ac-1", "COMPRA_CONFIRMADA", "u-1", 1);
    }

    @Test
    void sinTokenResponde403YNoGuarda() throws Exception {
        mvc.perform(post("/internal/notificaciones").contentType(MediaType.APPLICATION_JSON).content(avisoValido()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESO_DENEGADO"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void tokenIncorrectoResponde403YNoGuarda() throws Exception {
        mvc.perform(post("/internal/notificaciones").header(AutorizacionDeServicioFilter.ENCABEZADO, "otro-token")
                        .contentType(MediaType.APPLICATION_JSON).content(avisoValido()))
                .andExpect(status().isForbidden());

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void sinAutorizacionNiSiquieraSeValidaElCuerpo() throws Exception {
        mvc.perform(post("/internal/notificaciones").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenCorrectoPasa() throws Exception {
        mvc.perform(post("/internal/notificaciones").header(AutorizacionDeServicioFilter.ENCABEZADO, "token-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content(avisoValido()))
                .andExpect(status().isAccepted());

        assertThat(repositorio.count()).isEqualTo(1);
    }

    @Test
    void sinTokenConfiguradoRechazaTodo() {
        var filtro = new AutorizacionDeServicioFilter(new NotificacionesProperties.Seguridad(""),
                JsonMapper.builder().build());

        assertThat(filtro.autorizado("")).isFalse();
        assertThat(filtro.autorizado("cualquiera")).isFalse();
        assertThat(filtro.autorizado(null)).isFalse();
    }

    @Test
    void comparaElTokenCompleto() {
        var filtro = new AutorizacionDeServicioFilter(new NotificacionesProperties.Seguridad("secreto"),
                JsonMapper.builder().build());

        assertThat(filtro.autorizado("secreto")).isTrue();
        assertThat(filtro.autorizado("secret")).isFalse();
        assertThat(filtro.autorizado("secreto ")).isFalse();
        assertThat(filtro.autorizado("SECRETO")).isFalse();
    }
}
