package com.ticketya.gestionlugares.reservas.dto;

import java.util.List;

import com.ticketya.gestionlugares.domain.TipoSector;

public record SectorDisponibilidadRequest(
        String sectorId,
        TipoSector tipo,
        int capacidadTotal,
        List<AsientoReservas> asientos) {
}
