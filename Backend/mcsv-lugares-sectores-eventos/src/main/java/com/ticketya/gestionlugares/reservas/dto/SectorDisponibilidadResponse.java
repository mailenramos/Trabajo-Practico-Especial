package com.ticketya.gestionlugares.reservas.dto;

import java.util.List;

import com.ticketya.gestionlugares.domain.TipoSector;

public record SectorDisponibilidadResponse(String sectorId, TipoSector tipo, int capacidadTotal,
        int cantidadDisponible, List<AsientoDisponibleResponse> asientosDisponibles,
        List<AsientoDisponibleResponse> asientos) {
    public SectorDisponibilidadResponse(String sectorId, TipoSector tipo, int capacidadTotal,
            int cantidadDisponible, List<AsientoDisponibleResponse> asientosDisponibles) {
        this(sectorId, tipo, capacidadTotal, cantidadDisponible, asientosDisponibles, asientosDisponibles);
    }
}
