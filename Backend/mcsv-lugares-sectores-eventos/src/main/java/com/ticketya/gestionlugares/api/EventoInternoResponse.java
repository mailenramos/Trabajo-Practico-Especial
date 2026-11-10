package com.ticketya.gestionlugares.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ticketya.gestionlugares.domain.Evento;

public record EventoInternoResponse(String eventoId, String nombre, Instant fechaInicio,
        String estado, List<SectorEventoInternoResponse> sectores) {

    public static EventoInternoResponse from(Evento evento) {
        return new EventoInternoResponse(evento.getId(), evento.getNombre(), evento.getFechaHora(),
                evento.getEstado().getValor(), evento.getSectores().stream()
                        .map(sector -> new SectorEventoInternoResponse(sector.getSectorId(),
                                sector.getNombre(), sector.getPrecio()))
                        .toList());
    }

    public record SectorEventoInternoResponse(String sectorId, String nombre, BigDecimal precio) { }
}
