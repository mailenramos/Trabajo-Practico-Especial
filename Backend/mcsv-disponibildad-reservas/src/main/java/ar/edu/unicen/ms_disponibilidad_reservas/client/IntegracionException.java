package ar.edu.unicen.ms_disponibilidad_reservas.client;

/** Failure categories are kept separate so callers can handle uncertain outcomes safely. */
public class IntegracionException extends RuntimeException {
    public enum Tipo { NO_ENCONTRADO, CONFLICTO, AUTORIZACION_NO_VIGENTE, NO_DISPONIBLE, RESPUESTA_INVALIDA }

    private final Tipo tipo;

    public IntegracionException(Tipo tipo, String mensaje) {
        super(mensaje);
        this.tipo = tipo;
    }

    public Tipo tipo() { return tipo; }
}
