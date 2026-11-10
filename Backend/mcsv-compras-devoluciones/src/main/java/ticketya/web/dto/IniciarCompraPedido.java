package ticketya.web.dto;

import jakarta.validation.constraints.NotBlank;

public record IniciarCompraPedido(@NotBlank(message = "es obligatorio") String reservaId) {
}
