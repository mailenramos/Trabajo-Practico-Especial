package com.ticketya.gestionlugares.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadResponse;

import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.TipoSector;

public record EventoDetalleResponse(String id, String nombre, String descripcion, String tipo,
        String organizadorId, List<String> participantes, Instant fechaHora, String estado,
        boolean ventaSuspendida, LugarResponse lugar, List<SectorResponse> sectores) {
    public static EventoDetalleResponse from(Evento evento, Lugar lugar,
            Map<String, SectorDisponibilidadResponse> disponibilidadPorSector) {
        return new EventoDetalleResponse(evento.getId(), evento.getNombre(), evento.getDescripcion(),
                evento.getTipo(), evento.getOrganizadorId(), evento.getParticipantes(), evento.getFechaHora(),
                evento.getEstado().getValor(), evento.isVentaSuspendida(), LugarResponse.from(lugar),
                evento.getSectores().stream().map(sector -> SectorResponse.from(sector,
                        disponibilidadPorSector.get(sector.getSectorId()))).toList());
    }

    public record LugarResponse(String id, String nombre, String direccion, String ciudad) {
        static LugarResponse from(Lugar lugar) {
            return new LugarResponse(lugar.getId(), lugar.getNombre(), lugar.getDireccion(), lugar.getCiudad());
        }
    }

    public record SectorResponse(String sectorId, String nombre, TipoSector tipo, Integer filas,
            Integer asientosPorFila, Integer capacidad, BigDecimal precio, DisponibilidadSectorResponse disponibilidad) {
        static SectorResponse from(EventoSector sector, SectorDisponibilidadResponse disponibilidad) {
            return new SectorResponse(sector.getSectorId(), sector.getNombre(), sector.getTipo(),
                    sector.getFilas(), sector.getAsientosPorFila(), sector.getCapacidad(), sector.getPrecio(),
                    disponibilidad == null ? null : DisponibilidadSectorResponse.from(disponibilidad));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisponibilidadSectorResponse(int capacidadTotal, int cantidadDisponible,
            List<AsientoResponse> asientosDisponibles) {
        static DisponibilidadSectorResponse from(SectorDisponibilidadResponse disponibilidad) {
            List<AsientoResponse> asientos = disponibilidad.asientosDisponibles() == null ? null
                    : disponibilidad.asientosDisponibles().stream()
                            .map(asiento -> new AsientoResponse(asiento.fila(), asiento.numero())).toList();
            return new DisponibilidadSectorResponse(disponibilidad.capacidadTotal(),
                    disponibilidad.cantidadDisponible(), asientos);
        }
    }

    public record AsientoResponse(int fila, int numero) { }
}
