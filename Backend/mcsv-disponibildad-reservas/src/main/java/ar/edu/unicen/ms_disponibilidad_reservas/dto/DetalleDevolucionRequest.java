package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One numbered seat or a quantity from a non-numbered reservation detail. */
public record DetalleDevolucionRequest(
        @NotBlank(message = "es obligatorio") @Size(max = 255, message = "no puede superar 255 caracteres") String sectorId,
        Integer fila,
        @Positive(message = "debe ser un entero positivo") Integer numero,
        @Positive(message = "debe ser un entero positivo") Integer cantidad) {
}
