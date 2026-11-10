package ticketya.modelo;

/** Procesamiento del aviso de cancelación de un evento. */
public enum EstadoCancelacion {
    /** Aviso recibido; las compras del evento se están procesando. */
    EN_PROCESO,
    /** Todas las compras confirmadas del evento ya se procesaron. */
    PROCESADA
}
