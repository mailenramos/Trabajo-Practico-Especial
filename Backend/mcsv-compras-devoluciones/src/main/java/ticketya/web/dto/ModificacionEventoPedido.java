package ticketya.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Aviso de modificación de un evento que manda Eventos. {@code fechaHora} va como texto ISO-8601
 * (UTC) y se valida al recibirlo, para informar el error en "campos". Debe venir al menos uno de
 * fechaHora, lugar o ciudad.
 */
public record ModificacionEventoPedido(
        @NotBlank(message = "es obligatorio") String eventoId,
        @NotNull(message = "es obligatorio") @Min(value = 1, message = "debe ser un entero mayor o igual a 1") Integer version,
        String fechaHora,
        String lugar,
        String ciudad) {
}
