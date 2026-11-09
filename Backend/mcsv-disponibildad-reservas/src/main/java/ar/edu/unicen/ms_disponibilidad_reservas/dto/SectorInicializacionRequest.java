package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.util.List;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SectorInicializacionRequest(
        @NotBlank @Size(max = 255) String sectorId,
        @NotNull TipoSector tipo,
        @NotNull @PositiveOrZero Integer capacidadTotal,
        @NotNull List<@NotNull @Valid AsientoInicializacionRequest> asientos) {
}
