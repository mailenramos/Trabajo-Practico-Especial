package ticketya.repositorio;

import java.time.Instant;

/**
 * Un cliente con al menos una compra confirmada del evento, con el nombre y la fecha de inicio
 * del evento guardados en sus compras (los datos para armar su aviso).
 */
public record CompradorDeEvento(String clienteId, String eventoNombre, Instant eventoFechaInicio) {
}
