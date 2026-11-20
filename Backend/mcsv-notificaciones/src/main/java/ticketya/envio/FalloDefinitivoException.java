package ticketya.envio;

/** Fallo que reintentar no arregla (destinatario inexistente o sin correo): pasa a ERROR_FINAL. */
public class FalloDefinitivoException extends RuntimeException {

    public FalloDefinitivoException(String mensaje) {
        super(mensaje);
    }

    public FalloDefinitivoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
