package ar.edu.unicen.ms_disponibilidad_reservas.service;

/** Disponibilidad observada bajo bloqueo al rechazar una solicitud no numerada. */
public class DisponibilidadInsuficienteException extends DisponibilidadException {
    public DisponibilidadInsuficienteException(String sectorId, int disponibles) {
        super("Disponibilidad insuficiente en el sector " + sectorId + ". Lugares disponibles: " + disponibles + ".");
    }
}
