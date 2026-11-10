package ticketya.tarea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ticketya.servicio.CancelacionService;

/**
 * Reanudación automática de las cancelaciones de eventos interrumpidas. Eventos no reenvía
 * el aviso después de recibir 202, así que una cancelación que quedó en proceso (el servicio
 * se cayó o una compra falló) se retoma sola. Se desactiva con
 * compras.cancelacion.reanudacion-activa=false.
 */
@Component
@ConditionalOnProperty(name = ProgramacionConfig.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
public class ReanudacionDeCancelaciones {

    private static final Logger log = LoggerFactory.getLogger(ReanudacionDeCancelaciones.class);

    private final CancelacionService cancelacionService;

    public ReanudacionDeCancelaciones(CancelacionService cancelacionService) {
        this.cancelacionService = cancelacionService;
    }

    /** fixedDelay: la próxima revisión arranca cuando terminó la anterior, sin superponerse. */
    @Scheduled(fixedDelayString = "${compras.cancelacion.intervalo-reanudacion:60s}",
            initialDelayString = "${compras.cancelacion.intervalo-reanudacion:60s}")
    public void revisar() {
        int tomadas = cancelacionService.reanudarVencidas();
        if (tomadas > 0) {
            log.info("Reanudación automática: se retomaron {} cancelaciones de eventos", tomadas);
        }
    }
}
