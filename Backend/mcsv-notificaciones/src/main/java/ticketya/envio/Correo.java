package ticketya.envio;

/** Correo listo para enviar (texto plano). */
public record Correo(String destinatario, String asunto, String cuerpo) {
}
