package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ticketya.error.ApiException;
import ticketya.modelo.Notificacion;
import ticketya.repositorio.NotificacionRepository;
import ticketya.web.dto.AvisoPedido;

import java.time.Clock;
import java.time.Instant;

/**
 * Recepción de avisos: solo registra la notificación (PENDIENTE). El correo lo envía después
 * la tarea programada, así la solicitud nunca espera a Gestión de usuarios ni a SMTP.
 */
@Service
public class RecepcionService {

    private static final Logger log = LoggerFactory.getLogger(RecepcionService.class);

    private final NotificacionRepository repositorio;
    private final Clock reloj;

    public RecepcionService(NotificacionRepository repositorio, Clock reloj) {
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    /** Resultado de recibir un aviso: la notificación y si se creó ahora o ya existía (reentrega). */
    public record Recepcion(Notificacion notificacion, boolean duplicado) {
    }

    /**
     * Idempotente por clave (acontecimiento + tipo + destinatario): si el aviso ya se recibió,
     * devuelve la notificación existente sin crear otra. Si dos reentregas llegan a la vez, la
     * restricción única de la base deja pasar una sola y la otra devuelve la ya guardada.
     */
    public Recepcion recibir(AvisoPedido aviso) {
        if (!AvisoPedido.VERSIONES_SOPORTADAS.contains(aviso.versionEsquema())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ESQUEMA_NO_SOPORTADO",
                    "La versión de esquema " + aviso.versionEsquema() + " no está soportada. Versiones soportadas: "
                            + AvisoPedido.VERSIONES_SOPORTADAS);
        }
        String clave = Notificacion.claveDe(aviso.acontecimientoId(), aviso.tipoNotificacion(), aviso.usuarioId());
        var existente = repositorio.findByClaveDeduplicacion(clave);
        if (existente.isPresent()) {
            log.info("Aviso reentregado (clave {}): se devuelve la notificación {}", clave, existente.get().getId());
            return new Recepcion(existente.get(), true);
        }
        Notificacion nueva = Notificacion.nueva(aviso.acontecimientoId(), aviso.tipoNotificacion(),
                aviso.usuarioId(), aviso.recursoId(), aviso.correlacionId(), aviso.versionEsquema(),
                aviso.ocurridoEn(), aviso.datos(), Instant.now(reloj));
        try {
            Notificacion guardada = repositorio.saveAndFlush(nueva);
            log.info("Notificación {} registrada (tipo {}, correlación {})", guardada.getId(), guardada.getTipo(),
                    guardada.getCorrelacionId());
            return new Recepcion(guardada, false);
        } catch (DataIntegrityViolationException carrera) {
            // Otra solicitud con la misma clave se guardó entre la búsqueda y el insert
            Notificacion ganadora = repositorio.findByClaveDeduplicacion(clave).orElseThrow(() -> carrera);
            return new Recepcion(ganadora, true);
        }
    }
}
