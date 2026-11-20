package ticketya.modelo;

/**
 * Estado del envío de un aviso a Notificaciones, independiente de la operación que lo generó.
 * PROCESANDO dura mientras una ejecución de la tarea lo está enviando; si se cae, vence y se
 * recupera. ERROR_FINAL no se reintenta más.
 */
public enum EstadoAviso {
    PENDIENTE, PROCESANDO, ENVIADO, ERROR_FINAL
}
