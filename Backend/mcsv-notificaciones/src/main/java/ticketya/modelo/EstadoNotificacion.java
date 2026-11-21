package ticketya.modelo;

/**
 * PENDIENTE: espera su (próximo) intento. PROCESANDO: una tarea la tomó y la está enviando.
 * ENVIADA: SMTP aceptó el correo. ERROR_FINAL: no se reintenta más.
 */
public enum EstadoNotificacion {
    PENDIENTE,
    PROCESANDO,
    ENVIADA,
    ERROR_FINAL
}
