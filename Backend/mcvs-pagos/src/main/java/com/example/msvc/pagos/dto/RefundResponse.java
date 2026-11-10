package com.example.msvc.pagos.dto;

import com.example.msvc.pagos.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representación de un reintegro registrado.")
public record RefundResponse(
        @Schema(description = "Identificador del reintegro.", example = "33333333-3333-4333-8333-333333333333")
        UUID id,
        @Schema(description = "Identificador del pago asociado.", example = "11111111-1111-4111-8111-111111111111")
        UUID paymentId,
        @Schema(description = "Identificador de la solicitud originada por Compras.",
                example = "55555555-5555-4555-8555-555555555555")
        UUID refundRequestId,
        @Schema(description = "Importe reintegrado.", example = "500.00")
        BigDecimal amount,
        @Schema(description = "Resultado del reintegro.", example = "EXITOSO")
        RefundStatus status,
        @Schema(description = "Motivo del reintegro.", example = "Prueba de reintegro")
        String reason,
        @Schema(description = "Cantidad de intentos realizados.", example = "1")
        Integer attemptCount,
        @Schema(description = "Fecha y hora de creación en UTC.", example = "2026-01-01T12:00:00Z")
        Instant createdAt,
        @Schema(description = "Fecha y hora de última actualización en UTC.", example = "2026-01-01T12:00:00Z")
        Instant updatedAt) {
}
