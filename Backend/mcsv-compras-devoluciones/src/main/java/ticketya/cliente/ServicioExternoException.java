package ticketya.cliente;

/**
 * Falla técnica al hablar con otro servicio: no respondió, se cortó la conexión o
 * devolvió una respuesta inesperada. El resultado de la operación es incierto.
 */
public class ServicioExternoException extends RuntimeException {

    private final String servicio;

    public ServicioExternoException(String servicio, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.servicio = servicio;
    }

    public ServicioExternoException(String servicio, String mensaje) {
        this(servicio, mensaje, null);
    }

    public String getServicio() { return servicio; }
}
