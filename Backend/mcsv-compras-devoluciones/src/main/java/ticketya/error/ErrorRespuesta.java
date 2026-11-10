package ticketya.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Formato de error acordado con el grupo: {timestamp, status, error, mensaje, campos?}.
 * "error" lleva un código (por ejemplo COMPRA_NO_ENCONTRADA) y "campos" solo aparece
 * en los errores de validación.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorRespuesta(Instant timestamp, int status, String error, String mensaje,
                             Map<String, String> campos) {

    public static ErrorRespuesta de(int status, String error, String mensaje) {
        return new ErrorRespuesta(Instant.now(), status, error, mensaje, null);
    }
}
