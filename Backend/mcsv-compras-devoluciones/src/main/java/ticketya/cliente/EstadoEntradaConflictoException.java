package ticketya.cliente;

/**
 * Entradas rechazó un cambio de estado de una entrada por transición inválida (409
 * TRANSICION_INVALIDA): el servicio respondió, pero la entrada no está en un estado que
 * admita el cambio. Es un conflicto de estado, no una caída: repetir el pedido sin que cambie
 * el estado de la entrada no lo arregla. Es una {@link ServicioExternoException} para que los
 * caminos que registran un paso incompleto y lo retoman la traten igual, con su propio mensaje.
 */
public class EstadoEntradaConflictoException extends ServicioExternoException {

    private final String idEntrada;
    private final String codigo;

    public EstadoEntradaConflictoException(String idEntrada, String operacion, String codigo, Throwable causa) {
        super("Entradas", "Conflicto de estado de la entrada " + idEntrada + " en Entradas al " + operacion
                + " (" + (codigo == null ? "409 sin código" : codigo) + ")", causa);
        this.idEntrada = idEntrada;
        this.codigo = codigo;
    }

    public String getIdEntrada() { return idEntrada; }

    public String getCodigo() { return codigo; }
}
