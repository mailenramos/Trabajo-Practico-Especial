package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AsientoInicializacionRequest(
        @NotNull Integer fila,
        @NotNull @Positive Integer numero) {
}
