package ticketya.web;

/**
 * Usuario que hace el pedido, tomado de los encabezados X-User-Id y X-User-Role.
 * En la Entrega 1 no hay autenticación: los encabezados los completa el Gateway.
 */
public record UsuarioActual(String id, String rol) {
}
