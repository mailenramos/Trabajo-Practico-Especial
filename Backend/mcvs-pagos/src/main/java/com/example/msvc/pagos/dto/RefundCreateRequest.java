package com.example.msvc.pagos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Datos necesarios para registrar un reintegro.")
public record RefundCreateRequest(
        @Schema(description = "Identificador de la solicitud de reintegro generado por Compras.",
                example = "55555555-5555-4555-8555-555555555555", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID refundRequestId,
        @Schema(description = "Importe positivo del reintegro, con hasta dos decimales.",
                example = "500.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Schema(description = "Motivo del reintegro.", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "Prueba de reintegro") String reason) {
}
