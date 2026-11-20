package ticketya.servicio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ticketya.config.NotificacionesProperties;

import java.time.Duration;

/**
 * Espera exponencial entre intentos: base, 2·base, 4·base... con un tope. Tras el máximo de
 * intentos la notificación pasa a ERROR_FINAL.
 */
@Component
public class PoliticaDeReintentos {

    private final int maximoIntentos;
    private final Duration esperaBase;
    private final Duration esperaMaxima;

    @Autowired
    public PoliticaDeReintentos(NotificacionesProperties propiedades) {
        this(propiedades.reintentos().maximoIntentos(), propiedades.reintentos().esperaBase(),
                propiedades.reintentos().esperaMaxima());
    }

    PoliticaDeReintentos(int maximoIntentos, Duration esperaBase, Duration esperaMaxima) {
        this.maximoIntentos = maximoIntentos;
        this.esperaBase = esperaBase;
        this.esperaMaxima = esperaMaxima;
    }

    public int maximoIntentos() {
        return maximoIntentos;
    }

    /** ¿Se agotaron los intentos tras haber hecho {@code intentosHechos}? */
    public boolean agotados(int intentosHechos) {
        return intentosHechos >= maximoIntentos;
    }

    /** Espera antes del próximo intento, después de {@code intentosHechos} intentos fallidos (desde 1). */
    public Duration esperaTras(int intentosHechos) {
        int exponente = Math.max(0, Math.min(intentosHechos - 1, 30));
        Duration espera = esperaBase.multipliedBy(1L << exponente);
        return espera.compareTo(esperaMaxima) > 0 ? esperaMaxima : espera;
    }
}
