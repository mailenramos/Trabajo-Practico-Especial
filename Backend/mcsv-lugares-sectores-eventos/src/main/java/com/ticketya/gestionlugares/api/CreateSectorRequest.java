package com.ticketya.gestionlugares.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

import com.ticketya.gestionlugares.domain.TipoSector;

@Schema(name = "CreateSectorRequest", description = "Configuración de un sector numerado o no numerado.")
public record CreateSectorRequest(
        @Schema(description = "Nombre del sector.", example = "Platea", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String nombre,
        @Schema(description = "Tipo de sector.", allowableValues = {"NUMERADO", "NO_NUMERADO"},
                example = "NUMERADO", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull TipoSector tipo,
        @Schema(description = "Cantidad de filas; requerido para NUMERADO.", example = "20", minimum = "1")
        Integer filas,
        @Schema(description = "Asientos por fila; requerido para NUMERADO.", example = "30", minimum = "1")
        Integer asientosPorFila,
        @Schema(description = "Capacidad; requerido para NO_NUMERADO.", example = "500", minimum = "1")
        Integer capacidad) {
}
