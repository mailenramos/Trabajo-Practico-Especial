package ticketya.cliente;

import ticketya.envio.FalloDefinitivoException;
import ticketya.envio.FalloTemporalException;

/** Gestión de usuarios (MS6): datos de contacto del destinatario. */
public interface UsuariosCliente {

    /**
     * Correo del usuario.
     *
     * @throws FalloDefinitivoException si el usuario no existe o no tiene correo
     * @throws FalloTemporalException   si MS6 no responde, falla o el circuit breaker está abierto
     */
    String correoDe(String usuarioId);
}
