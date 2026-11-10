package ticketya.cliente;

/**
 * Otro servicio respondió, pero con datos en un formato que no respeta el contrato (por
 * ejemplo, una fila que no es un número). Reintentar no lo arregla, a diferencia de un
 * servicio caído. Es una {@link ServicioExternoException} para que los caminos que ya
 * manejan una falla de otro servicio (reintentos, pasos pendientes) la traten igual.
 */
public class ContratoInvalidoException extends ServicioExternoException {

    public ContratoInvalidoException(String servicio, String mensaje, Throwable causa) {
        super(servicio, mensaje, causa);
    }

    public ContratoInvalidoException(String servicio, String mensaje) {
        super(servicio, mensaje);
    }
}
