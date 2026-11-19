package com.ticketya.preferencias.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ActualizarPreferenciasRequest(
        @NotNull(message = "La lista tiposEvento es obligatoria")
        List<@NotBlank(message = "El valor no puede quedar vacío") String> tiposEvento,
        @NotNull(message = "La lista artistas es obligatoria")
        List<@NotBlank(message = "El valor no puede quedar vacío") String> artistas,
        @NotNull(message = "La lista ciudades es obligatoria")
        List<@NotBlank(message = "El valor no puede quedar vacío") String> ciudades) {
}
