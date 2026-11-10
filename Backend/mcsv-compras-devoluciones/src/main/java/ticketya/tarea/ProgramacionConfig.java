package ticketya.tarea;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita las tareas programadas solo si la reanudación de cancelaciones está activa. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ProgramacionConfig.PROPIEDAD_ACTIVA, havingValue = "true", matchIfMissing = true)
public class ProgramacionConfig {

    public static final String PROPIEDAD_ACTIVA = "compras.cancelacion.reanudacion-activa";
}
