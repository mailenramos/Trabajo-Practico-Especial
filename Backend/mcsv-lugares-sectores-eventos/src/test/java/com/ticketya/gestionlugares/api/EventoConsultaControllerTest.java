package com.ticketya.gestionlugares.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.ticketya.gestionlugares.service.EventoConsultaService;
import com.ticketya.gestionlugares.service.UnauthorizedException;
import com.ticketya.gestionlugares.service.ForbiddenException;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;

@WebMvcTest(EventoConsultaController.class)
@Import(ApiExceptionHandler.class)
class EventoConsultaControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private EventoConsultaService consultaService;

    @Test
    void getEventosDevuelvePaginaYPropagaFiltros() throws Exception {
        when(consultaService.buscar(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new EventoBusquedaResponse(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/eventos").queryParam("tipo", "MUSICA")
                        .header("X-User-Id", "cliente-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isArray())
                .andExpect(jsonPath("$.contenido").isEmpty())
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(20))
                .andExpect(jsonPath("$.totalElementos").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(0));
    }

    @Test
    void parametrosInvalidosResponden400ConFormatoComun() throws Exception {
        when(consultaService.buscar(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("orden debe ser fecha, precio o relevancia"));
        mockMvc.perform(get("/eventos").queryParam("orden", "popularidad")
                        .header("X-User-Id", "cliente-1").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").value("orden debe ser fecha, precio o relevancia"));
    }

    @Test
    void busquedaResponde401SinIdentidadY403ConRolNoAdmitido() throws Exception {
        when(consultaService.buscar(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class))).thenAnswer(invocation -> {
                    String userId = invocation.getArgument(11);
                    String role = invocation.getArgument(12);
                    if (userId == null || role == null) {
                        throw new UnauthorizedException("Se requieren X-User-Id y X-User-Role");
                    }
                    throw new ForbiddenException("X-User-Role debe ser CLIENTE, ORGANIZADOR o ADMIN");
                });

        mockMvc.perform(get("/eventos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/eventos").header("X-User-Id", "u1").header("X-User-Role", "INVITADO"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void detallePublicoDevuelveDtoYRequiereHeaders() throws Exception {
        when(consultaService.detalle("evento-1", "org-1", "ORGANIZADOR"))
                .thenReturn(new EventoDetalleResponse("evento-1", "Show", "Desc", "MUSICA", "org-1",
                        List.of("Artista"), java.time.Instant.parse("2027-01-01T00:00:00Z"), "publicado", false,
                        new EventoDetalleResponse.LugarResponse("lugar-1", "Estadio", "Av. 1", "CABA"),
                        List.of(new EventoDetalleResponse.SectorResponse("sector-1", "Platea",
                                com.ticketya.gestionlugares.domain.TipoSector.NUMERADO, 1, 2, null,
                                new java.math.BigDecimal("15000"), null))));
        when(consultaService.detalle(eq("evento-1"), isNull(), isNull()))
                .thenThrow(new UnauthorizedException("Se requieren X-User-Id y X-User-Role"));
        when(consultaService.detalle(eq("evento-1"), eq("user-1"), eq("INVITADO")))
                .thenThrow(new ForbiddenException("X-User-Role debe ser CLIENTE, ORGANIZADOR o ADMIN"));
        when(consultaService.detalle(eq("missing"), eq("user-1"), eq("CLIENTE")))
                .thenThrow(new ResourceNotFoundException("No existe el evento missing"));
        mockMvc.perform(get("/eventos/evento-1").header("X-User-Id", "org-1")
                        .header("X-User-Role", "ORGANIZADOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("publicado"))
                .andExpect(jsonPath("$.lugar.ciudad").value("CABA"))
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/eventos/evento-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/eventos/evento-1").header("X-User-Id", "user-1")
                        .header("X-User-Role", "INVITADO"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(get("/eventos/missing").header("X-User-Id", "user-1")
                        .header("X-User-Role", "CLIENTE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
