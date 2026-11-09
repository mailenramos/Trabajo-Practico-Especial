package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import java.util.List;

/** Request for full payment-rejection release or a partial approved refund. */
public record LiberarReservaRequest(
        @NotNull(message = "es obligatorio")
        @Pattern(regexp = "PAGO_RECHAZADO|DEVOLUCION_APROBADA", message = "motivo no reconocido")
        String motivo,
        String devolucionId,
        List<@NotNull(message = "no puede ser null") @Valid DetalleDevolucionRequest> detalles) {
}
