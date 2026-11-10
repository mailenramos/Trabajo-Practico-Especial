package com.example.msvc.pagos.dto;

import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representación de un pago registrado.")
public record PaymentResponse(
        @Schema(description = "Identificador del pago.", example = "11111111-1111-4111-8111-111111111111")
        UUID id,
        @Schema(description = "Identificador de la compra.", example = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        UUID purchaseId,
        @Schema(description = "Importe cobrado.", example = "1500.00")
        BigDecimal amount,
        @Schema(description = "Medio de pago usado.", example = "CREDIT_CARD")
        PaymentMethod paymentMethod,
        @Schema(description = "Resultado del cobro.", example = "APROBADO")
        PaymentStatus status,
        @Schema(description = "Fecha y hora de creación en UTC.", example = "2026-01-01T12:00:00Z")
        Instant createdAt,
        @Schema(description = "Fecha y hora de última actualización en UTC.", example = "2026-01-01T12:00:00Z")
        Instant updatedAt) {
}
