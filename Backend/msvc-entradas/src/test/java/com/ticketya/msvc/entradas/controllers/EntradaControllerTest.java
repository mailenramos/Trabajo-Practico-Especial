package com.ticketya.msvc.entradas.controllers;

import com.ticketya.msvc.entradas.dto.EntradasCompraResponseDTO;
import com.ticketya.msvc.entradas.dto.IngresoResponseDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.dto.SectorResumenDTO;
import com.ticketya.msvc.entradas.exception.AccesoDenegadoException;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import com.ticketya.msvc.entradas.exception.ServicioNoDisponibleException;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;
import com.ticketya.msvc.entradas.services.EntradaService;
import com.ticketya.msvc.entradas.services.IngresoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EntradaController.class,
        properties = "entradas.seguridad.service-token=secreto-de-servicio")
class EntradaControllerTest {

    private static final String TOKEN = "secreto-de-servicio";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EntradaService entradaService;

    @MockitoBean
    private IngresoService ingresoService;

    // ---------- Identidad y roles ----------

    @Test
    void consultaSinIdentidad_responde401() throws Exception {
        mvc.perform(get("/entradas"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("FALTA_IDENTIFICACION"));
    }

    @Test
    void leeTodosLosRolesDeXUserRoles() throws Exception {
        when(entradaService.getEntradas(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PaginaEntradasResponseDTO(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/entradas").header("X-User-Id", "u-1").header("X-User-Roles", "CLIENTE, admin"))
                .andExpect(status().isOk());

        ArgumentCaptor<UsuarioActual> usuario = ArgumentCaptor.forClass(UsuarioActual.class);
        verify(entradaService).getEntradas(usuario.capture(), any(), any(), any(), anyInt(), anyInt());
        assertEquals("u-1", usuario.getValue().id());
        assertEquals(Set.of("CLIENTE", "ADMIN"), usuario.getValue().roles());
    }

    @Test
    void sinXUserRolesUsaElEncabezadoDeLaEntrega1() throws Exception {
        when(entradaService.getEntradas(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PaginaEntradasResponseDTO(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/entradas").header("X-User-Id", "u-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isOk());

        ArgumentCaptor<UsuarioActual> usuario = ArgumentCaptor.forClass(UsuarioActual.class);
        verify(entradaService).getEntradas(usuario.capture(), any(), any(), any(), anyInt(), anyInt());
        assertEquals(Set.of("CLIENTE"), usuario.getValue().roles());
    }

    // ---------- Validación de QR ----------

    @Test
    void validacionExitosa_responde201ConLaUbicacion() throws Exception {
        UUID qr = UUID.randomUUID();
        when(ingresoService.validar(any(), eq("evento-1"), eq(qr))).thenReturn(new IngresoResponseDTO(
                UUID.randomUUID(), UUID.randomUUID(), "evento-1", new SectorResumenDTO("s-1", "Platea"),
                3, 12, "personal-1", false, Instant.parse("2026-12-15T20:30:00Z")));

        mvc.perform(post("/eventos/evento-1/ingresos")
                        .header("X-User-Id", "personal-1").header("X-User-Roles", "PERSONAL_ACCESO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoQr\":\"" + qr + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sector.nombre").value("Platea"))
                .andExpect(jsonPath("$.fila").value(3))
                .andExpect(jsonPath("$.validadoEl").value("2026-12-15T20:30:00Z"));
    }

    @Test
    void validacionSinQrOConQrInvalido_responde400() throws Exception {
        mvc.perform(post("/eventos/evento-1/ingresos")
                        .header("X-User-Id", "p").header("X-User-Roles", "PERSONAL_ACCESO")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.codigoQr").exists());
        mvc.perform(post("/eventos/evento-1/ingresos")
                        .header("X-User-Id", "p").header("X-User-Roles", "PERSONAL_ACCESO")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"codigoQr\":\"no-es-uuid\"}"))
                .andExpect(status().isBadRequest());
        verify(ingresoService, never()).validar(any(), anyString(), any());
    }

    @Test
    void mapeaLosRechazosDeLaValidacion() throws Exception {
        when(ingresoService.validar(any(), eq("ev-403"), any()))
                .thenThrow(new AccesoDenegadoException("PERSONAL_NO_AUTORIZADO", "sin asignación"));
        when(ingresoService.validar(any(), eq("ev-409"), any()))
                .thenThrow(new BusinessConflictException("ENTRADA_YA_UTILIZADA", "ya usada"));
        when(ingresoService.validar(any(), eq("ev-503"), any()))
                .thenThrow(new ServicioNoDisponibleException("AUTORIZACION_NO_DISPONIBLE", "MS1 caído", null));

        String cuerpo = "{\"codigoQr\":\"" + UUID.randomUUID() + "\"}";
        for (String[] caso : new String[][] {
                {"ev-403", "403", "PERSONAL_NO_AUTORIZADO"},
                {"ev-409", "409", "ENTRADA_YA_UTILIZADA"},
                {"ev-503", "503", "AUTORIZACION_NO_DISPONIBLE"}}) {
            mvc.perform(post("/eventos/" + caso[0] + "/ingresos")
                            .header("X-User-Id", "p").header("X-User-Roles", "PERSONAL_ACCESO")
                            .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().is(Integer.parseInt(caso[1])))
                    .andExpect(jsonPath("$.error").value(caso[2]))
                    .andExpect(jsonPath("$.mensaje").exists());
        }
    }

    // ---------- Rutas internas y X-Service-Token ----------

    @Test
    void rutaInternaSinTokenOConTokenIncorrecto_responde401SinEjecutar() throws Exception {
        mvc.perform(post("/internal/eventos/evento-1/entradas/reintegro"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_AUTENTICADO"));
        mvc.perform(post("/internal/eventos/evento-1/entradas/reintegro").header("X-Service-Token", "otro"))
                .andExpect(status().isUnauthorized());
        verify(entradaService, never()).reintegroPorCancelacionEvento(anyString());
    }

    @Test
    void cierreConToken_respondeCerrado() throws Exception {
        when(entradaService.reintegroPorCancelacionEvento("evento-1"))
                .thenReturn(new ReintegroEventoResponseDTO("evento-1", 2, true));

        mvc.perform(post("/internal/eventos/evento-1/entradas/reintegro").header("X-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cerrado").value(true))
                .andExpect(jsonPath("$.entradasMarcadas").value(2));
    }

    @Test
    void consultaPorCompraConToken_devuelveLaLista() throws Exception {
        UUID idCompra = UUID.randomUUID();
        when(entradaService.consultarEntradasDeCompra(idCompra))
                .thenReturn(new EntradasCompraResponseDTO(idCompra, List.of()));

        mvc.perform(get("/internal/compras/" + idCompra + "/entradas").header("X-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(idCompra.toString()))
                .andExpect(jsonPath("$.entradas").isEmpty());
    }

    @Test
    void rutasPublicasNoExigenTokenDeServicio() throws Exception {
        when(entradaService.getEntradas(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PaginaEntradasResponseDTO(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/entradas").header("X-User-Id", "u-1").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isOk());
    }

    // ---------- Correlación ----------

    @Test
    void devuelveElIdentificadorDeCorrelacionRecibidoOGeneraUno() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        mvc.perform(get("/entradas").header("X-Correlation-Id", correlationId))
                .andExpect(header().string("X-Correlation-Id", correlationId));

        String generado = mvc.perform(get("/entradas").header("X-Correlation-Id", "no-es-uuid"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertEquals(generado, UUID.fromString(generado).toString());
    }
}
