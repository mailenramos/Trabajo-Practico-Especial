package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ticketya.PruebaIntegracion;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.TipoAviso;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /internal/eventos-modificados: códigos 202, 200, 400 y 403. */
class EventosModificadosTest extends PruebaIntegracion {

    private static final String RUTA = "/internal/eventos-modificados";
    private static final Instant FECHA_VIEJA = Instant.parse("2026-12-01T21:00:00Z");

    private ResultActions avisar(String cuerpo) throws Exception {
        return mvc.perform(post(RUTA).header(AutorizacionDeServicioFilter.ENCABEZADO, TOKEN_SERVICIO)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private Compra compraConfirmada(String clienteId) {
        Compra c = new Compra();
        c.setClienteId(clienteId);
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId("A");
        c.setEventoNombre("Evento A");
        c.setEventoFechaInicio(FECHA_VIEJA);
        c.setTotal(new BigDecimal("1000.00"));
        c.setEstado(EstadoCompra.CONFIRMADA);
        return compras.saveAndFlush(c);
    }

    private void nadaGuardado(Compra c) {
        assertThat(modificacionesDeEvento.count()).isZero();
        assertThat(avisos.count()).isZero();
        assertThat(compras.findById(c.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_VIEJA);
    }

    @Test
    void unaModificacionNuevaResponde202YAplicaLosCambios() throws Exception {
        Compra c = compraConfirmada("c1");

        avisar("""
                {"eventoId":"A","version":1,"fechaHora":"2026-12-15T23:30:00Z","lugar":"Estadio Norte","ciudad":"Rosario"}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventoId").value("A"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.resultado").value("ACEPTADA"));

        assertThat(compras.findById(c.getId()).orElseThrow().getEventoFechaInicio())
                .isEqualTo(Instant.parse("2026-12-15T23:30:00Z"));
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_MODIFICADO))
                .extracting(AvisoPendiente::getUsuarioId).containsExactly("c1");
    }

    @Test
    void soloConLugarTambienSeAcepta() throws Exception {
        Compra c = compraConfirmada("c1");

        avisar("{\"eventoId\":\"A\",\"version\":1,\"lugar\":\"Teatro Sur\"}")
                .andExpect(status().isAccepted());

        assertThat(compras.findById(c.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_VIEJA);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_MODIFICADO)).hasSize(1);
    }

    @Test
    void laMismaVersionResponde200YaProcesada() throws Exception {
        compraConfirmada("c1");
        String cuerpo = "{\"eventoId\":\"A\",\"version\":1,\"fechaHora\":\"2026-12-15T23:30:00Z\"}";
        avisar(cuerpo).andExpect(status().isAccepted());

        avisar(cuerpo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("YA_PROCESADA"));

        assertThat(avisos.count()).isEqualTo(1);
    }

    @Test
    void unaVersionAnteriorResponde200() throws Exception {
        compraConfirmada("c1");
        avisar("{\"eventoId\":\"A\",\"version\":3,\"ciudad\":\"Rosario\"}").andExpect(status().isAccepted());

        avisar("{\"eventoId\":\"A\",\"version\":2,\"ciudad\":\"Mendoza\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("VERSION_ANTERIOR"));

        assertThat(avisos.count()).isEqualTo(1);
    }

    @Test
    void sinEventoIdOVersionResponde400ConCampos() throws Exception {
        Compra c = compraConfirmada("c1");

        avisar("{\"version\":1,\"lugar\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.eventoId").exists());
        avisar("{\"eventoId\":\"A\",\"lugar\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.version").exists());
        avisar("{\"eventoId\":\"A\",\"version\":0,\"lugar\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.version").exists());

        nadaGuardado(c);
    }

    @Test
    void sinNingunCambioResponde400() throws Exception {
        Compra c = compraConfirmada("c1");

        avisar("{\"eventoId\":\"A\",\"version\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.fechaHora").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").exists());
        avisar("{\"eventoId\":\"A\",\"version\":1,\"lugar\":\"  \",\"ciudad\":\"\"}")
                .andExpect(status().isBadRequest());

        nadaGuardado(c);
    }

    @Test
    void unaFechaInvalidaResponde400() throws Exception {
        Compra c = compraConfirmada("c1");

        avisar("{\"eventoId\":\"A\",\"version\":1,\"fechaHora\":\"15/12/2026 20:30\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.fechaHora").exists());

        nadaGuardado(c);
    }

    @Test
    void sinTokenOConTokenIncorrectoResponde403SinGuardarNada() throws Exception {
        Compra c = compraConfirmada("c1");
        String cuerpo = "{\"eventoId\":\"A\",\"version\":1,\"fechaHora\":\"2026-12-15T23:30:00Z\"}";

        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_AUTORIZADO"));
        mvc.perform(post(RUTA).header(AutorizacionDeServicioFilter.ENCABEZADO, "otro")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());

        nadaGuardado(c);
    }
}
