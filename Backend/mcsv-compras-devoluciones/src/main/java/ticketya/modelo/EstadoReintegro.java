package ticketya.modelo;

/**
 * Estado del reintegro, independiente del de la solicitud de devolución.
 * PENDIENTE solo dura entre registrar el pedido y recibir la respuesta de Pagos:
 * Pagos siempre termina un reintegro como EXITOSO o FALLIDO en la misma llamada.
 */
public enum EstadoReintegro {
    PENDIENTE, EXITOSO, FALLIDO
}
