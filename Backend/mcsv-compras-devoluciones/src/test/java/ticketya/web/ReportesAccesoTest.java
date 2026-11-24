package ticketya.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Acceso a los cuatro reportes: titularidad del evento, roles y fallas de Eventos. */
class ReportesAccesoTest extends PruebaReportes {

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void elOrganizadorTitularVeLosReportesDeSuEvento(String reporte) throws Exception {
        comoOrganizador(reporte)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventoId").value(EVENTO_ID))
                .andExpect(jsonPath("$.eventoNombre").value("Recital de prueba"))
                .andExpect(jsonPath("$.estadoEvento").value("publicado"))
                .andExpect(jsonPath("$.generadoEn").exists())
                .andExpect(jsonPath("$.hayOperacionesPendientes").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void elAdministradorVeLosReportesDeCualquierEvento(String reporte) throws Exception {
        comoAdmin(reporte).andExpect(status().isOk()).andExpect(jsonPath("$.eventoId").value(EVENTO_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void unOrganizadorAjenoRecibe403SinDatosDelEvento(String reporte) throws Exception {
        reporte(OTRO_ORGANIZADOR, "ORGANIZADOR", reporte)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EVENTO_AJENO"))
                .andExpect(content().string(not(containsString("Recital de prueba"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void unClienteRecibe403SinConsultarAEventos(String reporte) throws Exception {
        eventos.setCaido(true); // si se consultara a Eventos, la respuesta sería 503

        reporte(ORGANIZADOR, "CLIENTE", reporte)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SIN_PERMISO_REPORTES"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void sinUsuarioIdentificadoResponde401(String reporte) throws Exception {
        mvc.perform(get("/reportes/eventos/{eventoId}/" + reporte, EVENTO_ID).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("USUARIO_NO_IDENTIFICADO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void unEventoInexistenteResponde404(String reporte) throws Exception {
        mvc.perform(get("/reportes/eventos/{eventoId}/" + reporte, "no-existe")
                        .header("X-User-Id", ADMIN).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("EVENTO_NO_ENCONTRADO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas", "devoluciones", "recaudacion", "ocupacion"})
    void conEventosCaidoResponde503YAlVolverResponde200(String reporte) throws Exception {
        eventos.setCaido(true);
        comoOrganizador(reporte)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_DISPONIBLE"));

        eventos.setCaido(false);
        comoOrganizador(reporte).andExpect(status().isOk());
    }
}
