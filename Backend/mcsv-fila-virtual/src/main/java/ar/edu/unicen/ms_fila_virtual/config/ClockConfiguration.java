package ar.edu.unicen.ms_fila_virtual.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
