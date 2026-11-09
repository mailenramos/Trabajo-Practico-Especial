package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Un asiento numerado o una cantidad de un sector no numerado. */
public record DetalleReservaSolicitud(
        @NotBlank(message = "es obligatorio") String sectorId,
        Integer fila,
        @Positive(message = "debe ser un entero positivo") Integer numero,
        @Positive(message = "debe ser un entero positivo") Integer cantidad) {
}
