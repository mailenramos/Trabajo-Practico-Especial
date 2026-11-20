package ticketya.envio;

/** Envía un correo. Implementación real: SMTP ({@link EnvioDeCorreoSmtp}); en los tests, un doble. */
public interface EnvioDeCorreo {

    /**
     * @throws FalloTemporalException   si el servidor de correo no está disponible o el circuit breaker está abierto
     * @throws FalloDefinitivoException si el correo no puede enviarse nunca (por ejemplo, dirección inválida)
     */
    void enviar(Correo correo);
}
