package ticketya.error;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio que se responde al cliente con el formato del grupo.
 * El código va en el campo "error" (por ejemplo COMPRA_NO_ENCONTRADA).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;

    public ApiException(HttpStatus status, String codigo, String mensaje) {
        super(mensaje);
        this.status = status;
        this.codigo = codigo;
    }

    public HttpStatus getStatus() { return status; }
    public String getCodigo() { return codigo; }
}
