package ar.edu.unicen.ms_disponibilidad_reservas.service;

public class ParametroInvalidoException extends IllegalArgumentException {
    public ParametroInvalidoException(String parametro) {
        super(parametro + " debe tener formato UUID válido.");
    }
}
