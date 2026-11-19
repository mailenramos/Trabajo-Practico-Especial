package com.ticketya.preferencias.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ticketya.preferencias.config.PreferenciasWebConfiguration;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.integration.compras.ComprasClient;
import com.ticketya.preferencias.integration.compras.ComprasUnavailableException;
import com.ticketya.preferencias.integration.compras.SenalesCompras;
import com.ticketya.preferencias.integration.eventos.EventoPublicado;
import com.ticketya.preferencias.integration.eventos.EventosCatalogoUnavailableException;
import com.ticketya.preferencias.integration.eventos.EventosClient;
import com.ticketya.preferencias.security.UserIdentity;
import com.ticketya.preferencias.service.PreferenciasService;
import com.ticketya.preferencias.service.RecomendacionesService;

@WebMvcTest(RecomendacionesController.class)
@Import({ApiExceptionHandler.class, PreferenciasWebConfiguration.class, RecomendacionesService.class})
class RecomendacionesControllerTest {
    private static final String RUTA = "/recomendaciones/me";
    private static final String USUARIO_ID = "cliente-1";
    private static final Instant AHORA = Instant.parse("2026-11-19T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreferenciasService preferenciasService;

    @MockitoBean
    private EventosClient eventosClient;

    @MockitoBean
    private ComprasClient comprasClient;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void configurarDependencias() {
        when(clock.instant()).thenReturn(AHORA);
    }

    @Test
    void requiereAmbosHeadersYRechazaUsuariosSinRolCliente() throws Exception {
        mockMvc.perform(get(RUTA).header(UserIdentity.USER_ROLES_HEADER, "CLIENTE"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get(RUTA).header(UserIdentity.USER_ID_HEADER, USUARIO_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "ORGANIZADOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(preferenciasService, never()).obtener(USUARIO_ID);
    }

    @Test
    void clienteAutenticadoRecibeRecomendaciones() throws Exception {
        configurarCatalogoYPreferencias();
        when(comprasClient.obtenerSenales(USUARIO_ID)).thenReturn(SenalesCompras.vacias());

        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, " ORGANIZADOR, cliente "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventoId").value("evento-1"))
                .andExpect(jsonPath("$[0].nombre").value("Concierto"))
                .andExpect(jsonPath("$[0].tipo").value("Rock"))
                .andExpect(jsonPath("$[0].participantes[0]").value("Artista"))
                .andExpect(jsonPath("$[0].ciudad").value("Rosario"))
                .andExpect(jsonPath("$[0].fechaHora").value("2026-11-20T12:00:00Z"))
                .andExpect(jsonPath("$[0].estado").value("publicado"))
                .andExpect(jsonPath("$[0].puntuacion").value(5));

        verify(preferenciasService).obtener(USUARIO_ID);
        verify(comprasClient).obtenerSenales(USUARIO_ID);
    }

    @Test
    void fallaDeComprasConservaRespuesta200YUsaSoloPreferencias() throws Exception {
        configurarCatalogoYPreferencias();
        when(comprasClient.obtenerSenales(USUARIO_ID))
                .thenThrow(new ComprasUnavailableException("Compras no disponible"));

        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventoId").value("evento-1"))
                .andExpect(jsonPath("$[0].puntuacion").value(5))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void fallaDeMs1Responde503ConFormatoDeErrorEstandar() throws Exception {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(preferencias());
        when(eventosClient.listarTodos())
                .thenThrow(new EventosCatalogoUnavailableException("MS1 no disponible"));

        mockMvc.perform(get(RUTA)
                        .header(UserIdentity.USER_ID_HEADER, USUARIO_ID)
                        .header(UserIdentity.USER_ROLES_HEADER, "CLIENTE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.mensaje").value("El catálogo de eventos no está disponible"));

        verify(comprasClient, never()).obtenerSenales(USUARIO_ID);
    }

    private void configurarCatalogoYPreferencias() {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(preferencias());
        when(eventosClient.listarTodos()).thenReturn(List.of(new EventoPublicado(
                "evento-1", "Concierto", "Rock", List.of("Artista"), "Rosario",
                Instant.parse("2026-11-20T12:00:00Z"), "publicado"),
                new EventoPublicado("evento-2", "Otro evento", "Jazz", List.of(), "Buenos Aires",
                        Instant.parse("2026-11-21T12:00:00Z"), "publicado")));
    }

    private Preferencia preferencias() {
        return new Preferencia(USUARIO_ID, List.of(), List.of("Artista"), List.of(), null);
    }
}
