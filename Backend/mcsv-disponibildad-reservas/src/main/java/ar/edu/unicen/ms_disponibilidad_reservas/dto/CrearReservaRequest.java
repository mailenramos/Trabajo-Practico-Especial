package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Solicitud idempotente para crear una reserva temporal. */
public record CrearReservaRequest(
        @NotNull(message = "es obligatorio") @Size(min = 1, message = "es obligatorio") String solicitudId,
        @NotEmpty(message = "debe incluir al menos un detalle") List<@NotNull(message = "no puede ser nulo") @Valid DetalleReservaSolicitud> detalles) {
}
