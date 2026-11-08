package com.ticketya.gestionlugares.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.service.EventoService;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.UnauthorizedException;

@WebMvcTest(EventoController.class)
@Import(ApiExceptionHandler.class)
class EventoControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private EventoService eventoService;

    @Test
    void creaEventoUsandoLimitePorDefectoYErrorComun() throws Exception {
        Evento evento = Evento.borrador("org-1", "lugar-1", "CABA", "Festival", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        when(eventoService.crearEvento(any(), any(), any())).thenReturn(evento);

        mockMvc.perform(post("/eventos")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Festival","descripcion":"Descripción","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("borrador"))
                .andExpect(jsonPath("$.limiteEntradasPorCliente").value(4));

        ArgumentCaptor<CreateEventoRequest> request = ArgumentCaptor.forClass(CreateEventoRequest.class);
        verify(eventoService).crearEvento(request.capture(), eq("org-1"), eq("organizador"));
        org.junit.jupiter.api.Assertions.assertEquals(4, request.getValue().limiteEntradasPorCliente());
    }

    @Test
    void altaConLimiteNullExplicitoUsaValorPorDefecto() throws Exception {
        Evento evento = Evento.borrador("org-1", "lugar-1", "CABA", "Festival", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        when(eventoService.crearEvento(any(), any(), any())).thenReturn(evento);

        mockMvc.perform(post("/eventos")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Festival","descripcion":"Descripción","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1","limiteEntradasPorCliente":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.limiteEntradasPorCliente").value(4));

        ArgumentCaptor<CreateEventoRequest> request = ArgumentCaptor.forClass(CreateEventoRequest.class);
        verify(eventoService).crearEvento(request.capture(), eq("org-1"), eq("organizador"));
        org.junit.jupiter.api.Assertions.assertEquals(4, request.getValue().limiteEntradasPorCliente());
    }

    @Test
    void errorDeValidacionUsaFormatoComunConListaDeCampos() throws Exception {
        mockMvc.perform(post("/eventos")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"","descripcion":"Desc","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1","limiteEntradasPorCliente":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").exists())
                .andExpect(jsonPath("$.campos[0].campo").exists())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void altaConLugarInexistenteResponde404ConFormatoComun() throws Exception {
        when(eventoService.crearEvento(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("No existe el lugar"));
        mockMvc.perform(post("/eventos")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Festival","descripcion":"Desc","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.campos").doesNotExist())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void modificaLimitePorRutaDeBorrador() throws Exception {
        Evento evento = Evento.borrador("org-1", "lugar-1", "CABA", "Festival", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 8, List.of());
        when(eventoService.actualizarLimiteEntradas(any(), anyInt(), any(), any())).thenReturn(evento);
        mockMvc.perform(put("/eventos/evento-1/limite-entradas-por-cliente")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limiteEntradasPorCliente\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limiteEntradasPorCliente").value(8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2.5", "\"2\""})
    void altaRechazaLimiteCeroNegativoDecimalYTexto(String limite) throws Exception {
        String body = """
                {"nombre":"Festival","descripcion":"Desc","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1","limiteEntradasPorCliente":%s}
                """.formatted(limite);

        mockMvc.perform(post("/eventos")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").exists());
        verifyNoInteractions(eventoService);
    }

    @Test
    void actualizacionSinLimiteRechazaCampoOmitido() throws Exception {
        mockMvc.perform(put("/eventos/evento-1/limite-entradas-por-cliente")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").exists());
        verifyNoInteractions(eventoService);
    }

    @Test
    void actualizacionSinCampoBodyRechazaSolicitudVacia() throws Exception {
        mockMvc.perform(put("/eventos/evento-1/limite-entradas-por-cliente")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").exists());
        verifyNoInteractions(eventoService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2.5", "\"2\"", "null"})
    void actualizacionRechazaLimiteCeroNegativoDecimalTextoONullExplicito(String limite) throws Exception {
        mockMvc.perform(put("/eventos/evento-1/limite-entradas-por-cliente")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limiteEntradasPorCliente\":" + limite + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").exists());
        verifyNoInteractions(eventoService);
    }

    @Test
    void precioUsaSectorDelEventoYHeadersDeOrganizador() throws Exception {
        Evento evento = Evento.borrador("org-1", "lugar-1", "CABA", "Festival", "Descripción",
                "MUSICA", List.of("Artista"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        when(eventoService.asignarPrecio(any(), any(), any(), any(), any())).thenReturn(evento);
        mockMvc.perform(put("/eventos/evento-1/sectores/sector-1/precio")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"precio\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("borrador"));
    }

    @Test
    void sectorDeOtroLugarResponde404ConMensajeYFormatoComun() throws Exception {
        when(eventoService.asignarPrecio(any(), any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException(
                        "El sector sector-ajeno no pertenece al lugar del evento"));

        mockMvc.perform(put("/eventos/evento-1/sectores/sector-ajeno/precio")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"precio\":15000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(
                        "El sector sector-ajeno no pertenece al lugar del evento"))
                .andExpect(jsonPath("$.campos").doesNotExist());
    }

    @Test
    void erroresSinCamposOmitenLaListaYLaFaltaDeIdentidadEs401() throws Exception {
        when(eventoService.crearEvento(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("No existe el lugar"));
        mockMvc.perform(post("/eventos")
                        .header("X-User-Id", "org-1").header("X-User-Role", "organizador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Festival","descripcion":"Desc","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.campos").doesNotExist());
        doThrow(new UnauthorizedException("Se requieren X-User-Id y X-User-Role"))
                .when(eventoService).crearEvento(any(), isNull(), isNull());
        mockMvc.perform(post("/eventos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Festival","descripcion":"Desc","tipo":"MUSICA","participantes":["Artista"],"fechaHora":"2027-01-01T00:00:00Z","lugarId":"lugar-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(401));
    }
}
