package ticketya.cliente;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Gestión de notificaciones (MS9): recibe los avisos de este servicio en
 * {@code POST /internal/notificaciones} (contrato en su openapi.yaml). Es idempotente por
 * acontecimiento + tipo + destinatario, así que reenviar un aviso es seguro.
 */
public interface NotificacionesCliente {

    /** Versión del esquema del aviso que admite Notificaciones. */
    int VERSION_ESQUEMA = 1;

    /**
     * Envía el aviso. Termina sin error si Notificaciones respondió 2xx (nuevo o duplicado).
     *
     * @throws AvisoRechazadoException si Notificaciones lo rechaza por inválido: reintentar no sirve
     * @throws ServicioExternoException si no respondió o respondió otro error: se puede reintentar
     */
    void enviar(Aviso aviso);

    record Aviso(String acontecimientoId, String tipo, int versionEsquema, Instant ocurridoEn,
                 String recursoId, String usuarioId, String correlacionId, JsonNode datos) {
    }

    /** Notificaciones rechazó el aviso por inválido (por ejemplo, 400): no se reintenta. */
    class AvisoRechazadoException extends RuntimeException {
        public AvisoRechazadoException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
