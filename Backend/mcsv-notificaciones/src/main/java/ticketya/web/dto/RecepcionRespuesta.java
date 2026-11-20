package ticketya.web.dto;

import ticketya.modelo.EstadoNotificacion;
import ticketya.servicio.RecepcionService.Recepcion;

import java.util.UUID;

/**
 * Confirmación de recepción del aviso (no de envío del correo).
 *
 * @param id        identificador de la notificación
 * @param estado    estado actual de la notificación
 * @param duplicado true si el aviso ya se había recibido antes (reentrega)
 */
public record RecepcionRespuesta(UUID id, EstadoNotificacion estado, boolean duplicado) {

    public static RecepcionRespuesta de(Recepcion recepcion) {
        return new RecepcionRespuesta(recepcion.notificacion().getId(), recepcion.notificacion().getEstado(),
                recepcion.duplicado());
    }
}
