package com.ticketya.gestionlugares.outbox;

import java.time.Instant;
import java.util.Map;

import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.Lugar;

public final class ModificacionAvisoOutboxFactory {

    private ModificacionAvisoOutboxFactory() {
    }

    public static AvisoOutbox crear(Evento evento, Lugar lugar, Instant ahora, ComprasProperties comprasProperties) {
        Long version = evento.getVersion();
        if (version == null || version < 1) {
            throw new IllegalStateException("El evento modificado debe tener una versión mayor o igual a 1");
        }
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("modificacion-" + evento.getId() + "-v" + version);
        aviso.setTipo("MODIFICACION");
        aviso.setEventoId(evento.getId());
        aviso.setDestino(comprasProperties.getBaseUrl().resolve("/internal/eventos-modificados").toString());
        aviso.setPayload(Map.of(
                "eventoId", evento.getId(),
                "version", version,
                "fechaHora", evento.getFechaHora().toString(),
                "lugar", lugar.getNombre(),
                "ciudad", evento.getCiudad()));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(ahora);
        aviso.setCreadoEn(ahora);
        aviso.setActualizadoEn(ahora);
        return aviso;
    }
}
