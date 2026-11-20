package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Autorización de servicio en /internal/**: token correcto, ausente, incorrecto y sin secreto configurado. */
class AutorizacionDeServicioFilterTest {

    private static final String TOKEN = "token-de-prueba";
    private final JsonMapper json = JsonMapper.builder().build();

    private MockHttpServletResponse llamar(String tokenConfigurado, String ruta, String tokenEnviado) throws Exception {
        AutorizacionDeServicioFilter filtro = new AutorizacionDeServicioFilter(tokenConfigurado, json);
        MockHttpServletRequest pedido = new MockHttpServletRequest("GET", ruta);
        if (tokenEnviado != null) {
            pedido.addHeader(AutorizacionDeServicioFilter.ENCABEZADO, tokenEnviado);
        }
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(pedido, respuesta, cadena);
        if (cadena.getRequest() != null) {
            respuesta.setStatus(200); // llegó al controlador
        }
        return respuesta;
    }

    private void esProhibido(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).isEqualTo(403);
        JsonNode cuerpo = json.readTree(r.getContentAsString());
        assertThat(cuerpo.get("status").asInt()).isEqualTo(403);
        assertThat(cuerpo.get("error").asString()).isEqualTo("SERVICIO_NO_AUTORIZADO");
        assertThat(cuerpo.get("mensaje").asString()).isNotBlank();
        assertThat(cuerpo.has("timestamp")).isTrue();
    }

    @Test
    void conElTokenCorrectoLaLlamadaPasa() throws Exception {
        assertThat(llamar(TOKEN, "/internal/eventos/e-1/co-compras", TOKEN).getStatus()).isEqualTo(200);
    }

    @Test
    void sinElEncabezadoResponde403() throws Exception {
        esProhibido(llamar(TOKEN, "/internal/eventos/e-1/co-compras", null));
    }

    @Test
    void conUnTokenIncorrectoResponde403() throws Exception {
        esProhibido(llamar(TOKEN, "/internal/eventos/e-1/co-compras", "otro-token"));
        esProhibido(llamar(TOKEN, "/internal/eventos/e-1/co-compras", TOKEN + "x"));
        esProhibido(llamar(TOKEN, "/internal/eventos/e-1/co-compras", ""));
    }

    @Test
    void sinSecretoConfiguradoRechazaTodasLasLlamadasInternas() throws Exception {
        esProhibido(llamar("", "/internal/usuarios/u-1/historial-preferencias", ""));
        esProhibido(llamar(null, "/internal/usuarios/u-1/historial-preferencias", "cualquiera"));
        esProhibido(llamar("   ", "/internal/usuarios/u-1/historial-preferencias", "   "));
    }

    @Test
    void lasRutasQueNoSonInternasNoSeControlan() throws Exception {
        assertThat(llamar(TOKEN, "/compras", null).getStatus()).isEqualTo(200);
        assertThat(llamar(TOKEN, "/eventos-cancelados", null).getStatus()).isEqualTo(200);
        assertThat(llamar(null, "/internalx/algo", null).getStatus()).isEqualTo(200);
    }
}
