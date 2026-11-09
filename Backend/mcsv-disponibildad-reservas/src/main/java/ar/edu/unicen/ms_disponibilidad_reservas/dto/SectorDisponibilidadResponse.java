package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectorDisponibilidadResponse(
        String sectorId,
        TipoSector tipo,
        Integer capacidadTotal,
        int cantidadDisponible,
        List<AsientoDisponibleResponse> asientosDisponibles) {
}
