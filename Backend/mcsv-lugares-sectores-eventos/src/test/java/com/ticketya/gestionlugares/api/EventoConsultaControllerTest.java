package com.ticketya.gestionlugares.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.ticketya.gestionlugares.service.EventoConsultaService;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.UnauthorizedException;

@WebMvcTest(EventoConsultaController.class)
@Import(ApiExceptionHandler.class)
class EventoConsultaControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private EventoConsultaService consultaService;

    @Test
    void busquedaPublicaDevuelvePaginaSinHeadersDeUsuario() throws Exception {
        when(consultaService.buscar(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(new EventoBusquedaResponse(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/eventos").queryParam("tipo", "MUSICA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isArray())
                .andExpect(jsonPath("$.contenido").isEmpty())
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(20))
                .andExpect(jsonPath("$.totalElementos").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(0));
    }

    @Test
    void busquedaPublicaIgnoraRolesDesconocidosComoSolicitudAnonima() throws Exception {
        when(consultaService.buscar(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(new EventoBusquedaResponse(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/eventos")
                        .queryParam("tipo", "MUSICA")
                        .header("X-User-Id", "user-1")
                        .header("X-User-Roles", "VISITANTE_DESCONOCIDO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isEmpty());

        verify(consultaService).buscar(eq("MUSICA"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void parametrosInvalidosResponden400ConFormatoComun() throws Exception {
        when(consultaService.buscar(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("orden debe ser fecha, precio o relevancia"));
        mockMvc.perform(get("/eventos").queryParam("orden", "popularidad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").value("orden debe ser fecha, precio o relevancia"));
    }

    @Test
    void detallePublicadoEsPublicoYBorradorSinIdentidadResponde401() throws Exception {
        EventoDetalleResponse detail = new EventoDetalleResponse("evento-1", "Show", "Desc", "MUSICA", "org-1",
                List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), "publicado", false,
                new EventoDetalleResponse.LugarResponse("lugar-1", "Estadio", "Av. 1", "CABA"),
                List.of(new EventoDetalleResponse.SectorResponse("sector-1", "Platea",
                        com.ticketya.gestionlugares.domain.TipoSector.NUMERADO, 1, 2, null,
                        new java.math.BigDecimal("15000"), null)));
        when(consultaService.detalle("evento-1", null, null)).thenReturn(detail);
        when(consultaService.detalle(eq("draft-1"), isNull(), isNull()))
                .thenThrow(new UnauthorizedException("Se requieren X-User-Id y X-User-Roles para consultar un borrador"));

        mockMvc.perform(get("/eventos/evento-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("publicado"))
                .andExpect(jsonPath("$.lugar.ciudad").value("CABA"));
        mockMvc.perform(get("/eventos/draft-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void detalleReenviaListaDeRolesParaConsultarBorrador() throws Exception {
        EventoDetalleResponse draft = new EventoDetalleResponse("draft-1", "Show", "Desc", "MUSICA", "owner-1",
                List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), "borrador", false,
                new EventoDetalleResponse.LugarResponse("lugar-1", "Estadio", "Av. 1", "CABA"), List.of());
        when(consultaService.detalle("draft-1", "owner-1", "CLIENTE,ORGANIZADOR")).thenReturn(draft);

        mockMvc.perform(get("/eventos/draft-1")
                        .header("X-User-Id", "owner-1")
                        .header("X-User-Roles", "CLIENTE,ORGANIZADOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("borrador"));

        verify(consultaService).detalle("draft-1", "owner-1", "CLIENTE,ORGANIZADOR");
    }

    @Test
    void detalleInexistenteResponde404SinRequerirToken() throws Exception {
        when(consultaService.detalle("missing", null, null))
                .thenThrow(new ResourceNotFoundException("No existe el evento missing"));

        mockMvc.perform(get("/eventos/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
