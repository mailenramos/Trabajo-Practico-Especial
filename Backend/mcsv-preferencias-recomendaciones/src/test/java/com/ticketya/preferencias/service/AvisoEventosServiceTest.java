package com.ticketya.preferencias.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketya.preferencias.api.EventoPublicadoAvisoRequest;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.integration.notificaciones.NotificacionRequest;
import com.ticketya.preferencias.integration.notificaciones.NotificacionesClient;
import com.ticketya.preferencias.integration.notificaciones.NotificacionesUnavailableException;
import com.ticketya.preferencias.repository.PreferenciasRepository;

class AvisoEventosServiceTest {
    private static final Instant AHORA = Instant.parse("2026-11-19T16:00:00Z");

    private final PreferenciasRepository preferenciasRepository = org.mockito.Mockito.mock(PreferenciasRepository.class);
    private final NotificacionesClient notificacionesClient = org.mockito.Mockito.mock(NotificacionesClient.class);
    private final AvisoEventosService avisoEventosService = new AvisoEventosService(
            preferenciasRepository, notificacionesClient, Clock.fixed(AHORA, ZoneOffset.UTC));

    @BeforeEach
    void limpiarMocks() {
        when(preferenciasRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void seleccionaSoloCoincidenciasSimultaneasPorArtistaYCiudadUnaVez() {
        when(preferenciasRepository.findAll()).thenReturn(List.of(
                preferencia("solo-artista", List.of("Artista"), List.of("Otra ciudad")),
                preferencia("solo-ciudad", List.of("Otro artista"), List.of("Rosario")),
                preferencia("ambos", List.of("ARTISTA", " Artista "), List.of(" ROSARIO ")),
                preferencia("ambos", List.of("Artista"), List.of("Rosario")),
                preferencia("sin-coincidencias", List.of("Otro"), List.of("Otra"))));

        var resultado = avisoEventosService.procesar(evento(List.of(" artista ")), "aviso-1");

        assertEquals(1, resultado.destinatarios());
        assertEquals(1, resultado.enviados());
        ArgumentCaptor<NotificacionRequest> captor = ArgumentCaptor.forClass(NotificacionRequest.class);
        verify(notificacionesClient).enviar(captor.capture());
        assertEquals("ambos", captor.getValue().usuarioId());
        assertEquals("publicacion-evento-1-ambos", captor.getValue().acontecimientoId());
        verify(notificacionesClient).enviar(any());
    }

    @Test
    void listaDeParticipantesVaciaONoCoincidenciasDevuelve202ConContadoresCero() {
        when(preferenciasRepository.findAll()).thenReturn(List.of(
                preferencia("cliente", List.of("Artista"), List.of("Rosario"))));

        var resultado = avisoEventosService.procesar(evento(List.of()), null);

        assertEquals(0, resultado.destinatarios());
        assertEquals(0, resultado.enviados());
        verify(notificacionesClient, never()).enviar(any());
    }

    @Test
    void generaUnaCorrelacionUuidCuandoNoLlegaAvisoId() {
        when(preferenciasRepository.findAll()).thenReturn(List.of(
                preferencia("cliente", List.of("Artista"), List.of("Rosario"))));

        avisoEventosService.procesar(evento(List.of("Artista")), " ");

        ArgumentCaptor<NotificacionRequest> captor = ArgumentCaptor.forClass(NotificacionRequest.class);
        verify(notificacionesClient).enviar(captor.capture());
        java.util.UUID.fromString(captor.getValue().correlacionId());
    }

    @Test
    void continuaConLosDemasDestinatariosTrasUnFalloYPropagaFalloParcial() {
        when(preferenciasRepository.findAll()).thenReturn(List.of(
                preferencia("cliente-1", List.of("Artista"), List.of("Rosario")),
                preferencia("cliente-2", List.of("Artista"), List.of("Rosario")),
                preferencia("cliente-3", List.of("Artista"), List.of("Rosario"))));
        doAnswer(invocation -> {
            NotificacionRequest notification = invocation.getArgument(0);
            if ("cliente-2".equals(notification.usuarioId())) {
                throw new NotificacionesUnavailableException("Notificaciones no disponible");
            }
            return null;
        }).when(notificacionesClient).enviar(any());

        NotificacionesPartialFailureException exception = assertThrows(
                NotificacionesPartialFailureException.class,
                () -> avisoEventosService.procesar(evento(List.of("Artista")), "trace-id"));

        assertEquals("No se pudieron entregar 1 de 3 avisos; 2 fueron aceptados", exception.getMessage());
        ArgumentCaptor<NotificacionRequest> captor = ArgumentCaptor.forClass(NotificacionRequest.class);
        verify(notificacionesClient, org.mockito.Mockito.times(3)).enviar(captor.capture());
        assertEquals(List.of("cliente-1", "cliente-2", "cliente-3"),
                captor.getAllValues().stream().map(NotificacionRequest::usuarioId).toList());
        assertEquals(List.of("trace-id", "trace-id", "trace-id"),
                captor.getAllValues().stream().map(NotificacionRequest::correlacionId).toList());
    }

    @Test
    void construyeCamposYFechaEnHoraDeBuenosAires() {
        when(preferenciasRepository.findAll()).thenReturn(List.of(
                preferencia("cliente-1", List.of("Artista"), List.of("Rosario"))));

        avisoEventosService.procesar(evento(List.of("Artista")), "aviso-recibido");

        ArgumentCaptor<NotificacionRequest> captor = ArgumentCaptor.forClass(NotificacionRequest.class);
        verify(notificacionesClient).enviar(captor.capture());
        NotificacionRequest notification = captor.getValue();
        assertEquals("NUEVO_EVENTO_DE_INTERES", notification.tipo());
        assertEquals(1, notification.versionEsquema());
        assertEquals(AHORA, notification.ocurridoEn());
        assertEquals("evento-1", notification.recursoId());
        assertEquals("aviso-recibido", notification.correlacionId());
        assertEquals(new NotificacionRequest.Datos("Concierto", "02/01/2030 00:04", "Rosario"),
                notification.datos());
    }

    @Test
    void identificadorDeMasDeCienCaracteresSeCuentaComoFalloYNoSeTrunca() {
        String idLargo = "evento-" + "x".repeat(90);
        EventoPublicadoAvisoRequest evento = new EventoPublicadoAvisoRequest(
                idLargo, "Concierto", "MUSICA", List.of("Artista"), "Rosario",
                Instant.parse("2030-01-02T03:04:05Z"));
        when(preferenciasRepository.findAll()).thenReturn(List.of(
                preferencia("cliente", List.of("Artista"), List.of("Rosario"))));

        assertThrows(NotificacionesPartialFailureException.class,
                () -> avisoEventosService.procesar(evento, null));
        verify(notificacionesClient, never()).enviar(any());
    }

    private Preferencia preferencia(String usuario, List<String> artistas, List<String> ciudades) {
        return new Preferencia(usuario, List.of("ROCK"), artistas, ciudades, null);
    }

    private EventoPublicadoAvisoRequest evento(List<String> participantes) {
        return new EventoPublicadoAvisoRequest("evento-1", "Concierto", "MUSICA",
                participantes, "Rosario", Instant.parse("2030-01-02T03:04:05Z"));
    }
}
