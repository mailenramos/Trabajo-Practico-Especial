package com.example.msvc.pagos.exceptions;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Formato común de los errores de la API.")
public record ApiError(
        @Schema(description = "Instante en que se produjo el error, en UTC.", example = "2026-01-01T12:00:00Z")
        Instant timestamp,
        @Schema(description = "Código HTTP devuelto.", example = "400")
        int status,
        @Schema(description = "Nombre estándar del estado HTTP.", example = "Bad Request")
        String error,
        @Schema(description = "Descripción del error.", example = "Error de validación")
        String mensaje,
        @Schema(description = "Detalle de errores de validación, presente solo cuando corresponde.",
                example = "{\"amount\":\"must be greater than 0\"}")
        Map<String, String> campos) {
}
