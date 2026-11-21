package ticketya.error;

import java.util.Map;

/**
 * Datos inválidos detectados fuera de Bean Validation (por ejemplo, reglas entre campos). Se
 * responde igual que una validación: 400 VALIDACION con el detalle en "campos".
 */
public class ValidacionException extends RuntimeException {

    private final Map<String, String> campos;

    public ValidacionException(Map<String, String> campos) {
        super("El pedido tiene datos inválidos");
        this.campos = Map.copyOf(campos);
    }

    public Map<String, String> getCampos() { return campos; }
}
