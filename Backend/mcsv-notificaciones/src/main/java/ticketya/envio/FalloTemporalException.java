package ticketya.envio;

/** Fallo que puede resolverse solo (servicio caído, timeout, circuit breaker abierto): se reintenta. */
public class FalloTemporalException extends RuntimeException {

    public FalloTemporalException(String mensaje) {
        super(mensaje);
    }

    public FalloTemporalException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
