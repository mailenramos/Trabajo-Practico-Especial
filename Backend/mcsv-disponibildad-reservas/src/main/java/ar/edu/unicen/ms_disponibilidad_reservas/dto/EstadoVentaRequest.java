package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import jakarta.validation.constraints.NotNull;

public record EstadoVentaRequest(@NotNull(message = "es obligatorio") EstadoVenta estado) {
}
