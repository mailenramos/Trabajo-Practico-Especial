package com.ticketya.preferencias.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketya.preferencias.api.RecomendacionResponse;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.integration.compras.ComprasClient;
import com.ticketya.preferencias.integration.compras.ComprasUnavailableException;
import com.ticketya.preferencias.integration.compras.SenalesCompras;
import com.ticketya.preferencias.integration.eventos.EventoPublicado;
import com.ticketya.preferencias.integration.eventos.EventosClient;

class RecomendacionesServiceTest {
    private static final String USUARIO_ID = "cliente-1";
    private static final Instant AHORA = Instant.parse("2026-11-19T12:00:00Z");

    private final PreferenciasService preferenciasService = mock(PreferenciasService.class);
    private final EventosClient eventosClient = mock(EventosClient.class);
    private final ComprasClient comprasClient = mock(ComprasClient.class);
    private RecomendacionesService recomendacionesService;

    @BeforeEach
    void configurarServicio() {
        recomendacionesService = new RecomendacionesService(
                preferenciasService,
                eventosClient,
                comprasClient,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(preferenciasService.obtener(USUARIO_ID))
                .thenReturn(new Preferencia(USUARIO_ID, List.of(), List.of(), List.of(), null));
        when(eventosClient.listarTodos()).thenReturn(List.of());
        when(comprasClient.obtenerSenales(USUARIO_ID)).thenReturn(SenalesCompras.vacias());
    }

    @Test
    void aplicaCadaPuntajePorCoincidencia() {
        assertEquals(5, puntuar(
                evento("artista", "MUSICA", List.of("Artista"), "Rosario", "publicado", "2026-11-20T00:00:00Z"),
                new Preferencia(USUARIO_ID, List.of(), List.of("Artista"), List.of(), null),
                SenalesCompras.vacias()));
        assertEquals(3, puntuar(
                evento("tipo", "MUSICA", List.of(), "Rosario", "publicado", "2026-11-20T00:00:00Z"),
                new Preferencia(USUARIO_ID, List.of("MUSICA"), List.of(), List.of(), null),
                SenalesCompras.vacias()));
        assertEquals(2, puntuar(
                evento("ciudad", "TEATRO", List.of(), "Rosario", "publicado", "2026-11-20T00:00:00Z"),
                new Preferencia(USUARIO_ID, List.of(), List.of(), List.of("Rosario"), null),
                SenalesCompras.vacias()));
        assertEquals(1, puntuar(
                evento("historial", "MUSICA", List.of(), "Rosario", "publicado", "2026-11-20T00:00:00Z"),
                preferenciasVacias(),
                new SenalesCompras(Set.of("MUSICA"), Set.of())));
        assertEquals(2, puntuar(
                evento("copra", "TEATRO", List.of(), "Rosario", "publicado", "2026-11-20T00:00:00Z"),
                preferenciasVacias(),
                new SenalesCompras(Set.of(), Set.of("copra"))));
    }

    @Test
    void normalizaComparacionesYSumaCadaReglaUnaSolaVez() {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(
                new Preferencia(USUARIO_ID, List.of(" rOcK "), List.of(" ARTISTA "),
                        List.of(" buenos aires "), null));
        when(eventosClient.listarTodos()).thenReturn(List.of(
                evento("EVENTO-1", "Rock", List.of("Artista", " artista "), "Buenos Aires",
                        "PUBLICADO", "2026-11-20T00:00:00Z")));
        when(comprasClient.obtenerSenales(USUARIO_ID))
                .thenReturn(new SenalesCompras(Set.of(" ROCK "), Set.of(" evento-1 ")));

        List<RecomendacionResponse> recomendaciones = recomendacionesService.recomendar(USUARIO_ID);

        assertEquals(1, recomendaciones.size());
        assertEquals(13, recomendaciones.get(0).puntuacion());
        assertEquals("PUBLICADO", recomendaciones.get(0).estado());
    }

    @Test
    void usuarioSinPreferenciasYSinSenalesDeComprasObtieneListaVacia() {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(preferenciasVacias());
        when(eventosClient.listarTodos()).thenReturn(List.of(
                evento("evento-1", "Rock", List.of("Artista"), "Rosario",
                        "publicado", "2026-11-20T00:00:00Z")));

        assertEquals(List.of(), recomendacionesService.recomendar(USUARIO_ID));
    }

    @Test
    void fallaDeComprasDescartaTodasSusSenalesYConservaSoloCoincidenciasDePreferencias() {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(
                new Preferencia(USUARIO_ID, List.of("ROCK"), List.of("Artista favorito"), List.of(), null));
        when(eventosClient.listarTodos()).thenReturn(List.of(
                evento("preferencia", "ROCK", List.of(), "Rosario", "publicado", "2026-11-20T00:00:00Z"),
                evento("solo-compra", "JAZZ", List.of(), "Rosario", "publicado", "2026-11-20T00:00:00Z")));
        when(comprasClient.obtenerSenales(USUARIO_ID))
                .thenThrow(new ComprasUnavailableException("Compras no disponible"));

        List<RecomendacionResponse> recomendaciones = recomendacionesService.recomendar(USUARIO_ID);

        assertEquals(List.of("preferencia"), recomendaciones.stream()
                .map(RecomendacionResponse::eventoId)
                .toList());
        assertEquals(3, recomendaciones.get(0).puntuacion());
    }

    @Test
    void excluyeEstadosNoVigentesFechasNoFuturasYComparaEstadoSinDistinguirMayusculas() {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(
                new Preferencia(USUARIO_ID, List.of("MUSICA"), List.of(), List.of(), null));
        when(eventosClient.listarTodos()).thenReturn(List.of(
                evento("publicado", "MUSICA", List.of(), "Rosario", "PUBLICADO", "2026-11-20T00:00:00Z"),
                evento("agotado", "MUSICA", List.of(), "Rosario", "Agotado", "2026-11-21T00:00:00Z"),
                evento("pasado", "MUSICA", List.of(), "Rosario", "publicado", "2026-11-18T00:00:00Z"),
                evento("ahora", "MUSICA", List.of(), "Rosario", "publicado", "2026-11-19T12:00:00Z"),
                evento("borrador", "MUSICA", List.of(), "Rosario", "borrador", "2026-11-22T00:00:00Z"),
                evento("cancelado", "MUSICA", List.of(), "Rosario", "cancelado", "2026-11-23T00:00:00Z")));

        List<RecomendacionResponse> recomendaciones = recomendacionesService.recomendar(USUARIO_ID);

        assertEquals(List.of("publicado", "agotado"), recomendaciones.stream()
                .map(RecomendacionResponse::eventoId)
                .toList());
    }

    @Test
    void ordenaPorPuntuacionFechaEIdYDevuelveComoMaximoVeinte() {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(
                new Preferencia(USUARIO_ID, List.of("MUSICA"), List.of("Artista"), List.of(), null));
        List<EventoPublicado> eventos = new ArrayList<>();
        eventos.add(evento("z-temprano", "MUSICA", List.of(), "Rosario",
                "publicado", "2026-11-20T00:00:00Z"));
        eventos.add(evento("b-empate", "MUSICA", List.of(), "Rosario",
                "agotado", "2026-11-21T00:00:00Z"));
        eventos.add(evento("a-empate", "MUSICA", List.of(), "Rosario",
                "publicado", "2026-11-21T00:00:00Z"));
        eventos.add(evento("mayor-puntaje", "MUSICA", List.of("Artista"), "Rosario",
                "publicado", "2026-11-22T00:00:00Z"));
        for (int i = 0; i < 22; i++) {
            eventos.add(evento(String.format("evento-%02d", i), "MUSICA", List.of(), "Rosario",
                    "publicado", "2026-11-23T00:00:00Z"));
        }
        when(eventosClient.listarTodos()).thenReturn(eventos);

        List<RecomendacionResponse> recomendaciones = recomendacionesService.recomendar(USUARIO_ID);

        assertEquals(20, recomendaciones.size());
        assertEquals(List.of("mayor-puntaje", "z-temprano", "a-empate", "b-empate"),
                recomendaciones.subList(0, 4).stream().map(RecomendacionResponse::eventoId).toList());
        assertEquals("evento-15", recomendaciones.get(19).eventoId());
    }

    private int puntuar(EventoPublicado evento, Preferencia preferencias, SenalesCompras senales) {
        when(preferenciasService.obtener(USUARIO_ID)).thenReturn(preferencias);
        when(eventosClient.listarTodos()).thenReturn(List.of(evento));
        when(comprasClient.obtenerSenales(USUARIO_ID)).thenReturn(senales);
        return recomendacionesService.recomendar(USUARIO_ID).get(0).puntuacion();
    }

    private Preferencia preferenciasVacias() {
        return new Preferencia(USUARIO_ID, List.of(), List.of(), List.of(), null);
    }

    private EventoPublicado evento(String id, String tipo, List<String> participantes, String ciudad,
            String estado, String fechaHora) {
        return new EventoPublicado(id, id, tipo, participantes, ciudad, Instant.parse(fechaHora), estado);
    }
}
