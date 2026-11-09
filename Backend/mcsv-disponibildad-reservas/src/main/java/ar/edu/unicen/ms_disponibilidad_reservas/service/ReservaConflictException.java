package ar.edu.unicen.ms_disponibilidad_reservas.service;

/** Conflicto de negocio al intentar reservar inventario o reutilizar una clave. */
public class ReservaConflictException extends RuntimeException {

    public ReservaConflictException(String message) {
        super(message);
    }
}
