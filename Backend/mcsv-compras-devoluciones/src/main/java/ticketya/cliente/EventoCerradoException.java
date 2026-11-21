package ticketya.cliente;

/**
 * Entradas rechazó la emisión porque el evento ya está cerrado (409 EVENTO_CERRADO): se canceló
 * y Entradas registró su cierre. Es un conflicto de negocio, no una falla técnica: reintentar la
 * emisión no lo cambia. Por eso NO es una {@link ServicioExternoException}.
 */
public class EventoCerradoException extends RuntimeException {

    private final String idEvento;

    public EventoCerradoException(String idEvento, Throwable causa) {
        super("Entradas rechazó la emisión: el evento " + idEvento + " está cerrado (EVENTO_CERRADO)", causa);
        this.idEvento = idEvento;
    }

    public String getIdEvento() { return idEvento; }
}
