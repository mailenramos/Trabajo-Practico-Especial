package com.ticketya.gestionlugares.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiError", description = "Error HTTP común del servicio.", example =
        "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":400,\"error\":\"Bad Request\",\"mensaje\":\"Solicitud inválida\",\"campos\":[{\"campo\":\"nombre\",\"mensaje\":\"no debe estar vacío\"}]}")
public record ApiError(Instant timestamp, int status, String error, String mensaje, List<ApiFieldError> campos) {
}
