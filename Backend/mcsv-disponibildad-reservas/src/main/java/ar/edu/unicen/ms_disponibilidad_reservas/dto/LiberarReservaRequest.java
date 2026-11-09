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
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "debe ser un UUID")
        String devolucionId,
        List<@NotNull(message = "no puede ser null") @Valid DetalleDevolucionRequest> detalles) {
}
