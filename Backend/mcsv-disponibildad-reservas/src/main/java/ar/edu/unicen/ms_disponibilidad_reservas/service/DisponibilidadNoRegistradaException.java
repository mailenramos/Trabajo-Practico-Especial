package ar.edu.unicen.ms_disponibilidad_reservas.service;

/** Indicates that MS2 has no local availability record for the requested event. */
public class DisponibilidadNoRegistradaException extends DisponibilidadException {

    public DisponibilidadNoRegistradaException(String message) {
        super(message);
    }
}
