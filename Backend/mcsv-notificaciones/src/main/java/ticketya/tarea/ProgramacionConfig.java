package ticketya.tarea;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita las tareas programadas, salvo con notificaciones.envio.activo=false (los tests las disparan a mano). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ProgramacionConfig.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
public class ProgramacionConfig {

    public static final String PROPIEDAD_ACTIVA = "notificaciones.envio.activo";
}
