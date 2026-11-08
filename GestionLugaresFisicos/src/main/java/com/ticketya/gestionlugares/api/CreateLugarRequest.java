package com.ticketya.gestionlugares.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreateLugarRequest", description = "Datos para registrar un lugar físico.")
public record CreateLugarRequest(
        @Schema(description = "Nombre del lugar.", example = "Teatro Central", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String nombre,
        @Schema(description = "Dirección del lugar.", example = "Av. Corrientes 1234", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String direccion,
        @Schema(description = "Ciudad donde se encuentra el lugar.", example = "Buenos Aires")
        String ciudad,
        @Schema(description = "Latitud entre -90 y 90 inclusive.", example = "-34.6037", minimum = "-90", maximum = "90",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitud,
        @Schema(description = "Longitud entre -180 y 180 inclusive.", example = "-58.3816", minimum = "-180", maximum = "180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitud) {
}
