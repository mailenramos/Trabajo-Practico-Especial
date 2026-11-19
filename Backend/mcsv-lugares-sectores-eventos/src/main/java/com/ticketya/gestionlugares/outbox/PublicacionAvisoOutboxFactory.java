package com.ticketya.gestionlugares.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.preferencias.http.PreferenciasProperties;

final class PublicacionAvisoOutboxFactory {

    private static final String DESTINO_PUBLICACION_PREFERENCIAS =
            "/internal/preferencias/eventos-publicados";

    private PublicacionAvisoOutboxFactory() {
    }

    static AvisoOutbox crear(Evento evento, Instant ahora, PreferenciasProperties preferenciasProperties) {
        List<String> participantes = evento.getParticipantes() == null
                ? List.of() : evento.getParticipantes();
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("publicacion-" + evento.getId());
        aviso.setTipo("PUBLICACION");
        aviso.setEventoId(evento.getId());
        aviso.setDestino(preferenciasProperties.getBaseUrl()
                .resolve(DESTINO_PUBLICACION_PREFERENCIAS).toString());
        aviso.setPayload(Map.of(
                "eventoId", evento.getId(),
                "nombre", evento.getNombre(),
                "tipo", evento.getTipo(),
                "participantes", participantes,
                "ciudad", evento.getCiudad(),
                "fechaHora", evento.getFechaHora().toString()));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(ahora);
        aviso.setCreadoEn(ahora);
        aviso.setActualizadoEn(ahora);
        return aviso;
    }
}
