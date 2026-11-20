package ar.edu.unicen.ms_fila_virtual.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ConfiguracionFilaRequest(
        @NotNull(message = "es obligatorio") @Positive(message = "debe ser mayor que 0") Integer cupo,
        @NotNull(message = "es obligatorio") @Positive(message = "debe ser mayor que 0") Integer duracionMinutos) {
}
