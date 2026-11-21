package ticketya.tarea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ticketya.servicio.EnvioService;

/** Toma las notificaciones pendientes y las envía. */
@Component
@ConditionalOnProperty(name = ProgramacionConfig.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
public class TareaDeEnvio {

    private static final Logger log = LoggerFactory.getLogger(TareaDeEnvio.class);

    private final EnvioService envio;

    public TareaDeEnvio(EnvioService envio) {
        this.envio = envio;
    }

    /** fixedDelay: la próxima ronda arranca cuando terminó la anterior, sin superponerse. */
    @Scheduled(fixedDelayString = "${notificaciones.envio.intervalo:10s}",
            initialDelayString = "${notificaciones.envio.intervalo:10s}")
    public void enviarPendientes() {
        try {
            int tomadas = envio.procesarPendientes();
            if (tomadas > 0) {
                log.info("Ronda de envío: se procesaron {} notificaciones", tomadas);
            }
        } catch (RuntimeException e) {
            // Por ejemplo, la base no responde: se reintenta en la próxima ronda
            log.error("Falló la ronda de envío", e);
        }
    }
}
