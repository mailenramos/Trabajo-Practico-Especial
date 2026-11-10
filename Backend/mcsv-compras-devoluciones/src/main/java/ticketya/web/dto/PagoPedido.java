package ticketya.web.dto;

import jakarta.validation.constraints.NotBlank;

/** medioDePago: CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER o DIGITAL_WALLET. */
public record PagoPedido(@NotBlank(message = "es obligatorio") String medioDePago) {
}
