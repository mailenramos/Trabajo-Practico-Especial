package ticketya.modelo;

/** Tipos de aviso que este servicio manda a Notificaciones (los nombres son los de su contrato). */
public enum TipoAviso {
    COMPRA_CONFIRMADA,
    ENTRADAS_DISPONIBLES,
    EVENTO_CANCELADO,
    EVENTO_MODIFICADO,
    DEVOLUCION_APROBADA,
    DEVOLUCION_RECHAZADA,
    RESULTADO_REINTEGRO
}
