package ticketya.repositorio;

/** Otro evento comprado por clientes que compraron el evento consultado, y cuántos clientes en común. */
public record CoCompra(String eventoId, long cantidad) {
}
