package ticketya.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import ticketya.cliente.UsuariosCliente;
import ticketya.cliente.rest.UsuariosClienteRest;
import ticketya.envio.EnvioDeCorreo;
import ticketya.envio.EnvioDeCorreoSmtp;
import ticketya.envio.FalloTemporalException;

import java.time.Duration;

/** Circuit breakers por dependencia (Gestión de usuarios y SMTP) y clientes que los usan. */
@Configuration
public class ResilienciaConfig {

    public static final String CIRCUITO_USUARIOS = "usuarios";
    public static final String CIRCUITO_SMTP = "smtp";

    /**
     * Abre el circuito si fallan al menos la mitad de las últimas 10 llamadas (con 5 como mínimo),
     * espera 30 s y prueba con 2 llamadas. Solo cuentan los fallos temporales: un destinatario
     * inexistente no indica que la dependencia esté caída.
     */
    public static CircuitBreakerConfig configuracionBase() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(FalloTemporalException.class)
                .build();
    }

    @Bean
    CircuitBreakerRegistry circuitBreakers() {
        return CircuitBreakerRegistry.of(configuracionBase());
    }

    @Bean
    UsuariosCliente usuariosCliente(NotificacionesProperties propiedades, CircuitBreakerRegistry circuitos) {
        var usuarios = propiedades.usuarios();
        return new UsuariosClienteRest(usuarios.url(), usuarios.timeoutConexion(), usuarios.timeoutLectura(),
                propiedades.seguridad().tokenServicio(), circuitos.circuitBreaker(CIRCUITO_USUARIOS));
    }

    @Bean
    EnvioDeCorreo envioDeCorreo(JavaMailSender smtp, NotificacionesProperties propiedades,
                                CircuitBreakerRegistry circuitos) {
        return new EnvioDeCorreoSmtp(smtp, propiedades.correo().remitente(), circuitos.circuitBreaker(CIRCUITO_SMTP));
    }
}
