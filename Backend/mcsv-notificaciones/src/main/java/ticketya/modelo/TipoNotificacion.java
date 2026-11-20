package ticketya.modelo;

/** Tipos de notificación que el servicio sabe generar (uno por plantilla). */
public enum TipoNotificacion {
    COMPRA_CONFIRMADA,
    ENTRADAS_DISPONIBLES,
    EVENTO_CANCELADO,
    EVENTO_MODIFICADO,
    NUEVO_EVENTO_DE_INTERES,
    DEVOLUCION_APROBADA,
    DEVOLUCION_RECHAZADA,
    RESULTADO_REINTEGRO
}
