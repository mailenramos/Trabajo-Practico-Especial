package ticketya.dobles;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** Reemplaza los clientes REST por los dobles de prueba. */
@TestConfiguration
public class DoblesConfig {

    @Bean @Primary
    public EventosFalso eventosFalso() { return new EventosFalso(); }

    @Bean @Primary
    public ReservasFalso reservasFalso() { return new ReservasFalso(); }

    @Bean @Primary
    public PagosFalso pagosFalso() { return new PagosFalso(); }

    @Bean @Primary
    public EntradasFalso entradasFalso() { return new EntradasFalso(); }

    /** El procesamiento en segundo plano corre en el mismo hilo: la prueba ve el resultado al volver. */
    @Bean @Primary
    public TaskExecutor ejecutorSincronico() { return new SyncTaskExecutor(); }
}
