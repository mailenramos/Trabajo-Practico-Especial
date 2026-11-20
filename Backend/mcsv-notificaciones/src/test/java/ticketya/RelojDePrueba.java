package ticketya;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj que los tests mueven a mano para probar próximos intentos y vencimientos. */
public class RelojDePrueba extends Clock {

    public static final Instant INICIO = Instant.parse("2026-11-17T12:00:00Z");

    private volatile Instant ahora = INICIO;

    public void poner(Instant instante) {
        ahora = instante;
    }

    public void avanzar(Duration duracion) {
        ahora = ahora.plus(duracion);
    }

    @Override
    public Instant instant() {
        return ahora;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
        return this;
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public RelojDePrueba relojDePrueba() {
            return new RelojDePrueba();
        }
    }
}
