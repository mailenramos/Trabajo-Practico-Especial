package ticketya.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RechazarDevolucionPedido(
        @NotBlank(message = "es obligatorio") @Size(max = 255, message = "tiene como máximo 255 caracteres")
        String motivo) {
}
