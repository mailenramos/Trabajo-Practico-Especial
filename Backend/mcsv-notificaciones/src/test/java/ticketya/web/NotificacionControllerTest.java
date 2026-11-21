package ticketya.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;
import ticketya.repositorio.NotificacionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificacionControllerTest {

    static final String RUTA = "/internal/notificaciones";

    @Autowired
    MockMvc mvc;

    @Autowired
    NotificacionRepository repositorio;

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    static String aviso(String acontecimiento, String tipo, String usuario, Object version) {
        return """
                {"acontecimientoId":"%s","tipo":"%s","versionEsquema":%s,
                 "ocurridoEn":"2026-11-17T12:00:00Z","recursoId":"compra-1","usuarioId":"%s",
                 "correlacionId":"corr-1","datos":{"evento":"Recital","cantidad":2}}"""
                .formatted(acontecimiento, tipo, version, usuario);
    }

    MockHttpServletRequestBuilder enviar(String cuerpo) {
        return post(RUTA).header(AutorizacionDeServicioFilter.ENCABEZADO, "token-de-prueba")
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    @Test
    void avisoNuevoQuedaPendienteYResponde202() throws Exception {
        String id = mvc.perform(enviar(aviso("ac-1", "COMPRA_CONFIRMADA", "u-1", 1)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.duplicado").value(false))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        Notificacion n = repositorio.findAll().getFirst();
        assertThat(n.getId()).hasToString(id);
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(n.getTipo()).isEqualTo(TipoNotificacion.COMPRA_CONFIRMADA);
        assertThat(n.getUsuarioId()).isEqualTo("u-1");
        assertThat(n.getDatos()).containsEntry("evento", "Recital");
        assertThat(n.getEnviadaEn()).isNull();
    }

    @Test
    void reentregaDevuelveLaMismaNotificacionSinCrearOtra() throws Exception {
        String primera = mvc.perform(enviar(aviso("ac-1", "COMPRA_CONFIRMADA", "u-1", 1)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = primera.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(enviar(aviso("ac-1", "COMPRA_CONFIRMADA", "u-1", 1)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.duplicado").value(true));

        assertThat(repositorio.count()).isEqualTo(1);
    }

    @Test
    void mismoAcontecimientoParaOtroDestinatarioEsNuevo() throws Exception {
        mvc.perform(enviar(aviso("ac-1", "EVENTO_CANCELADO", "u-1", 1))).andExpect(status().isAccepted());
        mvc.perform(enviar(aviso("ac-1", "EVENTO_CANCELADO", "u-2", 1)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicado").value(false));

        assertThat(repositorio.count()).isEqualTo(2);
    }

    @Test
    void tipoDesconocidoResponde400YNoGuarda() throws Exception {
        mvc.perform(enviar(aviso("ac-1", "COMPRA_ANULADA", "u-1", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.tipo").exists());

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void faltaUnCampoObligatorioResponde400YNoGuarda() throws Exception {
        mvc.perform(enviar("{\"tipo\":\"COMPRA_CONFIRMADA\",\"versionEsquema\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.campos.acontecimientoId").value("es obligatorio"))
                .andExpect(jsonPath("$.campos.usuarioId").value("es obligatorio"));

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void versionDeEsquemaNoSoportadaResponde400YNoGuarda() throws Exception {
        mvc.perform(enviar(aviso("ac-1", "COMPRA_CONFIRMADA", "u-1", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ESQUEMA_NO_SOPORTADO"))
                .andExpect(jsonPath("$.campos").doesNotExist());

        assertThat(repositorio.count()).isZero();
    }
}
