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

    @Bean @Primary
    public NotificacionesFalso notificacionesFalso() { return new NotificacionesFalso(); }

    /**
     * El procesamiento en segundo plano corre en el mismo hilo: la prueba ve el resultado al volver.
     * También se llama applicationTaskExecutor: al definir este ejecutor, Spring Boot no crea el suyo,
     * y CancelacionService lo pide por ese nombre.
     */
    @Bean(name = {"ejecutorSincronico", "applicationTaskExecutor"}) @Primary
    public TaskExecutor ejecutorSincronico() { return new SyncTaskExecutor(); }
}
