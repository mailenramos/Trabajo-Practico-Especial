package ticketya.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** Aviso de Eventos: el evento se canceló. Solo eventoId es obligatorio. */
public record CancelacionEventoPedido(@NotBlank(message = "es obligatorio") String eventoId,
                                      String motivo,
                                      Instant canceladoEn) {
}
