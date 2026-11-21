package com.ticketya.gestionlugares.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Al menos un campo; los campos omitidos conservan su valor.")
public record ModificarEventoRequest(
        @Schema(description = "Fecha y hora futura ISO-8601 UTC.", example = "2035-02-01T12:00:00Z") String fechaHora,
        @Schema(description = "ID de un lugar existente.", example = "507f1f77bcf86cd799439011") String lugarId) {
}
