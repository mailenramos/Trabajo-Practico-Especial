package ticketya.tarea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ticketya.servicio.RecuperacionService;

/** Devuelve al circuito las notificaciones que quedaron en PROCESANDO tras una caída. */
@Component
@ConditionalOnProperty(name = ProgramacionConfig.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
public class TareaDeRecuperacion {

    private static final Logger log = LoggerFactory.getLogger(TareaDeRecuperacion.class);

    private final RecuperacionService recuperacion;

    public TareaDeRecuperacion(RecuperacionService recuperacion) {
        this.recuperacion = recuperacion;
    }

    @Scheduled(fixedDelayString = "${notificaciones.envio.intervalo-recuperacion:60s}",
            initialDelayString = "${notificaciones.envio.intervalo-recuperacion:60s}")
    public void recuperar() {
        try {
            recuperacion.recuperarVencidas();
        } catch (RuntimeException e) {
            log.error("Falló la recuperación de notificaciones vencidas", e);
        }
    }
}
