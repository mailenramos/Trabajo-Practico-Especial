package com.ticketya.preferencias.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ticketya.preferencias.config.PreferenciasWebConfiguration;
import com.ticketya.preferencias.integration.compras.ComprasProperties;
import com.ticketya.preferencias.service.AvisoEventosService;
import com.ticketya.preferencias.service.NotificacionesPartialFailureException;

@WebMvcTest(AvisoEventosPublicadosController.class)
@Import({ApiExceptionHandler.class, PreferenciasWebConfiguration.class, AvisoTokenInterceptor.class})
class AvisoEventosPublicadosControllerTest {
    private static final String RUTA = "/internal/preferencias/eventos-publicados";
    private static final String TOKEN = "token-ms1";
    private static final String BODY_VALIDO = """
            {
              "eventoId": "evento-1",
              "nombre": "Concierto",
              "tipo": "MUSICA",
              "participantes": ["Artista"],
              "ciudad": "Rosario",
              "fechaHora": "2030-01-02T03:04:05Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvisoEventosService avisoEventosService;

    @MockitoBean
    private ComprasProperties comprasProperties;

    @BeforeEach
    void configurarToken() {
        when(comprasProperties.getServicioToken()).thenReturn(TOKEN);
        when(avisoEventosService.procesar(any(), any()))
                .thenReturn(new AvisoEventoResponse(1, 1));
    }

    @Test
    void rechazaTokenAusenteIncorrectoOCuandoNoEstaConfigurado() throws Exception {
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(post(RUTA).header("X-Service-Token", "incorrecto")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isForbidden());

        when(comprasProperties.getServicioToken()).thenReturn(" ");
        mockMvc.perform(post(RUTA).header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isForbidden());
        verify(avisoEventosService, never()).procesar(any(), any());
    }

    @Test
    void aceptaFormatoDeFechaDeMs1SinRequerirIdentidadDeUsuario() throws Exception {
        mockMvc.perform(post(RUTA)
                        .header("X-Service-Token", TOKEN)
                        .header("X-Aviso-Id", "aviso-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.destinatarios").value(1))
                .andExpect(jsonPath("$.enviados").value(1));

        var captor = org.mockito.ArgumentCaptor.forClass(EventoPublicadoAvisoRequest.class);
        verify(avisoEventosService).procesar(captor.capture(), eq("aviso-123"));
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), captor.getValue().fechaHora());
    }

    @Test
    void responde202ConContadoresCeroSiNoHayDestinatarios() throws Exception {
        when(avisoEventosService.procesar(any(), any()))
                .thenReturn(new AvisoEventoResponse(0, 0));

        mockMvc.perform(post(RUTA)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.destinatarios").value(0))
                .andExpect(jsonPath("$.enviados").value(0));
    }

    @Test
    void interpretaFechaSinZonaEnBuenosAires() throws Exception {
        String body = BODY_VALIDO.replace("2030-01-02T03:04:05Z", "2030-01-02T03:04:05");
        mockMvc.perform(post(RUTA)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        var captor = org.mockito.ArgumentCaptor.forClass(EventoPublicadoAvisoRequest.class);
        verify(avisoEventosService).procesar(captor.capture(), eq(null));
        assertEquals(Instant.parse("2030-01-02T06:04:05Z"), captor.getValue().fechaHora());
    }

    @Test
    void rechazaCuerpoInvalidoCon400() throws Exception {
        for (String body : new String[] {
                """
                        {"eventoId":"evento-1","nombre":"Concierto","tipo":"MUSICA",
                         "participantes":{},"ciudad":"Rosario","fechaHora":"2030-01-02T03:04:05Z"}
                        """,
                BODY_VALIDO.replace("\"participantes\": [\"Artista\"]", "\"participantes\": [42]"),
                BODY_VALIDO.replace("\"participantes\": [\"Artista\"]", "\"participantes\": [\"\"]"),
                BODY_VALIDO.replace("\"eventoId\": \"evento-1\"", "\"eventoId\": 1"),
                BODY_VALIDO.replace("2030-01-02T03:04:05Z", "no-es-fecha"),
                BODY_VALIDO.replace("\"nombre\": \"Concierto\",", "")
        }) {
            mockMvc.perform(post(RUTA)
                            .header("X-Service-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
        verify(avisoEventosService, never()).procesar(any(), any());
    }

    @Test
    void devuelve503ConErrorEstandarSiElServicioDetectaFallosParciales() throws Exception {
        when(avisoEventosService.procesar(any(), any()))
                .thenThrow(new NotificacionesPartialFailureException(2, 1, 1));

        mockMvc.perform(post(RUTA)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"));
    }
}
