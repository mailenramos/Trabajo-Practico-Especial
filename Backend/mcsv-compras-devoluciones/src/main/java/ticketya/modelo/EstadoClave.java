package ticketya.modelo;

public enum EstadoClave {
    /** Un pedido con esta clave se está procesando. */
    EN_PROCESO,
    /** Terminó con un resultado definitivo: un pedido repetido recibe ese resultado. */
    COMPLETADA,
    /**
     * Quedó a medias (otro servicio no respondió o falló la emisión de entradas).
     * Un pedido repetido con la misma clave retoma desde el último paso hecho.
     */
    INTERRUMPIDA
}
