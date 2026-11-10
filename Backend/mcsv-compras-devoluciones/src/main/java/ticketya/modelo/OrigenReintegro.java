package ticketya.modelo;

/** Por qué se pide el reintegro. */
public enum OrigenReintegro {
    /** Solicitud de devolución aprobada: el refundRequestId es el devolucionId. */
    DEVOLUCION,
    /** Evento cancelado: el refundRequestId lo genera este servicio. */
    CANCELACION_EVENTO,
    /** Pago aprobado pero venta rechazada (reserva vencida): lo genera este servicio. */
    VENTA_RECHAZADA
}
