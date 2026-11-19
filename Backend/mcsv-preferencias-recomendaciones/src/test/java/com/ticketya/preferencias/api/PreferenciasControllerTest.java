package com.ticketya.preferencias.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ticketya.preferencias.config.PreferenciasWebConfiguration;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.security.UserIdentity;
import com.ticketya.preferencias.service.PreferenciasInvalidasException;
import com.ticketya.preferencias.service.PreferenciasService;

@WebMvcTest(PreferenciasController.class)
@Import({ApiExceptionHandler.class, PreferenciasWebConfiguration.class})
class PreferenciasControllerTest {
    private static final String RUTA = "/preferencias/me";
    private static final String USUARIO_ID = "cliente-1";
    private static final String CUERPO = """
            {"tiposEvento":["Rock"],"artistas":["Artista"],"ciudades":["Rosario"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreferenciasService preferenciasService;

    @Test
    void requiereAmbosHeadersEnCadaEndpoint() throws Exception {
        List<MockHttpServletRequestBuilder> solicitudes = List.of(
                get(RUTA),
                get(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID),
                get(RUTA).header(UserIdentity.USER_ROLES_HEADER, "CLIENTE"),
                put(RUTA).header(UserIdentity.USER_ROLES_HEADER, "CLIENTE")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO),
                put(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO),
                put(RUTA).contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete(RUTA).header(UserIdentity.USER_ROLES_HEADER, "CLIENTE"),
                delete(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID));

        for (MockHttpServletRequestBuilder solicitud : solicitudes) {
            mockMvc.perform(solicitud)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Test
    void rechazaRolSinClienteEnCadaEndpoint() throws Exception {
        List<MockHttpServletRequestBuilder> solicitudes = List.of(
                get(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "ORGANIZADOR"),
                put(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "ORGANIZADOR")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO),
                delete(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "ORGANIZADOR"));

        for (MockHttpServletRequestBuilder solicitud : solicitudes) {
            mockMvc.perform(solicitud)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.mensaje").value("Se requiere el rol CLIENTE"));
        }

        verify(preferenciasService, never()).obtener(any());
        verify(preferenciasService, never()).reemplazar(any(), any());
        verify(preferenciasService, never()).eliminar(any());
    }

    @Test
    void clientePuedeConsultarConListaDeRolesRecortadaYSinDistinguirMayusculas() throws Exception {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(
                new Preferencia(USUARIO_ID, List.of("Rock"), List.of("Artista"),
                        List.of("Rosario"), Instant.parse("2026-11-18T12:00:00Z")));

        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, " ORGANIZADOR, cliente "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiposEvento[0]").value("Rock"))
                .andExpect(jsonPath("$.artistas[0]").value("Artista"))
                .andExpect(jsonPath("$.ciudades[0]").value("Rosario"))
                .andExpect(jsonPath("$.actualizadoEn").value("2026-11-18T12:00:00Z"));
    }

    @Test
    void clienteSinPreferenciasRecibeListasVaciasYFechaNula() throws Exception {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(
                new Preferencia(USUARIO_ID, List.of(), List.of(), List.of(), null));

        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"tiposEvento":[],"artistas":[],"ciudades":[],"actualizadoEn":null}
                        """));
    }

    @Test
    void clientePuedeReemplazarPreferenciasYRecibeLoGuardado() throws Exception {
        when(preferenciasService.reemplazar(eq(USUARIO_ID), any())).thenReturn(
                new Preferencia(USUARIO_ID, List.of("Rock"), List.of("Artista"),
                        List.of("Rosario"), Instant.parse("2026-11-18T12:00:00Z")));

        mockMvc.perform(put(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tiposEvento":[" Rock "],"artistas":["Artista"],"ciudades":["Rosario"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiposEvento[0]").value("Rock"))
                .andExpect(jsonPath("$.actualizadoEn").value("2026-11-18T12:00:00Z"));
    }

    @Test
    void clientePuedeEliminarPreferenciasYRecibe204() throws Exception {
        MockHttpServletRequestBuilder solicitud = delete(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE");

        mockMvc.perform(solicitud).andExpect(status().isNoContent()).andExpect(content().string(""));
        mockMvc.perform(solicitud).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(preferenciasService, times(2)).eliminar(USUARIO_ID);
    }

    @Test
    void rechazaListasAusentesONulasSinInvocarElServicio() throws Exception {
        for (String cuerpo : List.of(
                """
                        {"tiposEvento":[],"artistas":[]}
                        """,
                """
                        {"tiposEvento":null,"artistas":[],"ciudades":[]}
                        """)) {
            mockMvc.perform(put(RUTA)
                            .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                            .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.mensaje").value("Solicitud inválida"))
                    .andExpect(jsonPath("$.campos[0].campo").exists());
        }
        verify(preferenciasService, never()).reemplazar(any(), any());
    }

    @Test
    void valoresDuplicadosProducenErrorDeCampo() throws Exception {
        when(preferenciasService.reemplazar(eq(USUARIO_ID), any()))
                .thenThrow(new PreferenciasInvalidasException("tiposEvento",
                        "La lista tiposEvento no admite valores repetidos"));

        mockMvc.perform(put(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tiposEvento":["Rock","Rock"],"artistas":[],"ciudades":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("tiposEvento"))
                .andExpect(jsonPath("$.mensaje").value("La lista tiposEvento no admite valores repetidos"));
    }

    @Test
    void rolVacioEnHeaderPresenteNoEquivaleAHeaderAusente() throws Exception {
        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, ""))
                .andExpect(status().isForbidden());

        verify(preferenciasService, never()).obtener(any());
    }
}
