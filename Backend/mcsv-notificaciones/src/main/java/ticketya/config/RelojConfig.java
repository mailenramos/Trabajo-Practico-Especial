package ticketya.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Reloj inyectable: los tests lo reemplazan para controlar fechas de intento y vencimiento. */
@Configuration
public class RelojConfig {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }
}
