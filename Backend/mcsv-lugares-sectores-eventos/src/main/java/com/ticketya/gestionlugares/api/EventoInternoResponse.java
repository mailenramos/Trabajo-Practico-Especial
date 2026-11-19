package com.ticketya.gestionlugares.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.TipoSector;

public record EventoInternoResponse(String eventoId, String nombre, Instant fechaInicio,
        String estado, List<SectorEventoInternoResponse> sectores, String organizadorId,
        boolean requiereFila, String tipo, Instant fechaHora, String ciudad) {

    public static EventoInternoResponse from(Evento evento) {
        return new EventoInternoResponse(evento.getId(), evento.getNombre(), evento.getFechaHora(),
                evento.getEstado().getValor(), evento.getSectores().stream()
                        .map(sector -> new SectorEventoInternoResponse(sector.getSectorId(),
                                sector.getNombre(), sector.getPrecio(), sector.getTipo(), sector.getFilas(),
                                sector.getAsientosPorFila(), sector.getCapacidad()))
                        .toList(), evento.getOrganizadorId(), evento.isRequiereFila(),
                evento.getTipo(), evento.getFechaHora(), evento.getCiudad());
    }

    public record SectorEventoInternoResponse(String sectorId, String nombre, BigDecimal precio,
            TipoSector tipo, Integer filas, Integer asientosPorFila, Integer capacidad) { }
}
