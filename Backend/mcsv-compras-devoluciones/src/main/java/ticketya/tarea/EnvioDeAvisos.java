package ticketya.tarea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ticketya.servicio.EnvioDeAvisosService;

/**
 * Tarea programada que envía a Notificaciones los avisos del outbox. Se desactiva con
 * compras.avisos.envio-activo=false (independiente de la reanudación de cancelaciones).
 */
@Component
@ConditionalOnProperty(name = EnvioDeAvisos.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
public class EnvioDeAvisos {

    public static final String PROPIEDAD_ACTIVA = "compras.avisos.envio-activo";
    private static final Logger log = LoggerFactory.getLogger(EnvioDeAvisos.class);

    private final EnvioDeAvisosService envio;

    public EnvioDeAvisos(EnvioDeAvisosService envio) {
        this.envio = envio;
    }

    /**
     * Primero recupera los avisos interrumpidos y después envía los pendientes. fixedDelay: la
     * próxima ronda arranca cuando terminó la anterior, sin superponerse.
     */
    @Scheduled(fixedDelayString = "${compras.avisos.intervalo-envio:10s}",
            initialDelayString = "${compras.avisos.intervalo-envio:10s}")
    public void enviar() {
        envio.recuperarVencidos();
        int enviados = envio.enviarPendientes();
        if (enviados > 0) {
            log.info("Se enviaron {} avisos a Notificaciones", enviados);
        }
    }

    /** Habilita la programación para esta tarea aunque la reanudación de cancelaciones esté apagada. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = EnvioDeAvisos.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
    static class Programacion {
    }
}
