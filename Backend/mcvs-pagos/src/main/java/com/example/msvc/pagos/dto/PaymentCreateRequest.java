package com.example.msvc.pagos.dto;

import com.example.msvc.pagos.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Datos necesarios para registrar un cobro.")
public record PaymentCreateRequest(
        @Schema(description = "Identificador de la compra en el servicio de Compras.",
                example = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID purchaseId,
        @Schema(description = "Importe positivo, con hasta dos decimales.", example = "1500.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Schema(description = "Medio de pago utilizado para la simulación.", example = "CREDIT_CARD",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PaymentMethod paymentMethod) {
}
