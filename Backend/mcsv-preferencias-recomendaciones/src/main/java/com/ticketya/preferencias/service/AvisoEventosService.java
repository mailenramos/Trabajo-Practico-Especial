package com.ticketya.preferencias.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ticketya.preferencias.api.AvisoEventoResponse;
import com.ticketya.preferencias.api.EventoPublicadoAvisoRequest;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.integration.notificaciones.NotificacionRequest;
import com.ticketya.preferencias.integration.notificaciones.NotificacionesClient;
import com.ticketya.preferencias.integration.notificaciones.NotificacionesUnavailableException;
import com.ticketya.preferencias.repository.PreferenciasRepository;

@Service
public class AvisoEventosService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AvisoEventosService.class);
    private static final ZoneId ZONA_BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter FECHA_EVENTO_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).withZone(ZONA_BUENOS_AIRES);
    private static final String TIPO_AVISO = "NUEVO_EVENTO_DE_INTERES";
    private static final int MAX_ACONTECIMIENTO_ID = 100;

    private final PreferenciasRepository preferenciasRepository;
    private final NotificacionesClient notificacionesClient;
    private final Clock clock;

    public AvisoEventosService(PreferenciasRepository preferenciasRepository,
            NotificacionesClient notificacionesClient, Clock recommendationClock) {
        this.preferenciasRepository = preferenciasRepository;
        this.notificacionesClient = notificacionesClient;
        this.clock = recommendationClock;
    }

    public AvisoEventoResponse procesar(EventoPublicadoAvisoRequest evento, String avisoId) {
        List<Preferencia> destinatarios = preferenciasRepository.findAll().stream()
                .filter(preferencia -> preferencia.usuarioId() != null)
                .filter(preferencia -> coincide(preferencia, evento))
                .collect(Collectors.toMap(Preferencia::usuarioId, Function.identity(),
                        (primero, repetido) -> primero, LinkedHashMap::new))
                .values().stream()
                .toList();
        String correlacionId = avisoId == null || avisoId.isBlank() ? UUID.randomUUID().toString() : avisoId;
        int enviados = 0;
        int fallidos = 0;

        for (Preferencia destinatario : destinatarios) {
            String acontecimientoId = "publicacion-" + evento.eventoId() + "-" + destinatario.usuarioId();
            if (acontecimientoId.length() > MAX_ACONTECIMIENTO_ID) {
                fallidos++;
                continue;
            }

            try {
                notificacionesClient.enviar(crearNotificacion(evento, destinatario, acontecimientoId, correlacionId));
                enviados++;
            } catch (NotificacionesUnavailableException exception) {
                fallidos++;
            }
        }

        if (fallidos > 0) {
            LOGGER.warn("Fallaron {} de {} avisos a Notificaciones; {} fueron aceptados.",
                    fallidos, destinatarios.size(), enviados);
            throw new NotificacionesPartialFailureException(destinatarios.size(), enviados, fallidos);
        }
        return new AvisoEventoResponse(destinatarios.size(), enviados);
    }

    private boolean coincide(Preferencia preferencia, EventoPublicadoAvisoRequest evento) {
        boolean coincideArtista = preferencia.artistas() != null
                && evento.participantes().stream()
                        .map(AvisoEventosService::normalizar)
                        .filter(Objects::nonNull)
                        .anyMatch(participante -> preferencia.artistas().stream()
                                .map(AvisoEventosService::normalizar)
                                .filter(Objects::nonNull)
                                .anyMatch(participante::equals));
        boolean coincideCiudad = preferencia.ciudades() != null
                && preferencia.ciudades().stream()
                        .map(AvisoEventosService::normalizar)
                        .filter(Objects::nonNull)
                        .anyMatch(ciudad -> ciudad.equals(normalizar(evento.ciudad())));
        return coincideArtista && coincideCiudad;
    }

    private NotificacionRequest crearNotificacion(EventoPublicadoAvisoRequest evento,
            Preferencia destinatario, String acontecimientoId, String correlacionId) {
        Instant ahora = clock.instant();
        return new NotificacionRequest(
                acontecimientoId,
                TIPO_AVISO,
                1,
                ahora,
                evento.eventoId(),
                destinatario.usuarioId(),
                correlacionId,
                new NotificacionRequest.Datos(
                        evento.nombre(),
                        FECHA_EVENTO_FORMATTER.format(evento.fechaHora()),
                        evento.ciudad()));
    }

    private static String normalizar(String valor) {
        return valor == null ? null : valor.strip().toLowerCase(Locale.ROOT);
    }
}
