package ar.edu.unicen.ms_disponibilidad_reservas.model;

/** Durable delivery states for an MS2-to-MS1 availability notification. */
public enum EstadoNotificacionOutbox {
    PENDIENTE,
    PROCESANDO,
    ENVIADA
}
