package ticketya.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SolicitarDevolucionPedido(@NotBlank(message = "es obligatorio") String entradaId) {
}
