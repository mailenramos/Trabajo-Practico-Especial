package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ticketya.repositorio.NotificacionRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * Recupera las notificaciones que quedaron en PROCESANDO pasado su vencimiento (el servicio se
 * cayó durante el envío). El intento interrumpido cuenta: vuelven a PENDIENTE o, si ya agotaron
 * los intentos, pasan a ERROR_FINAL. Si el correo sí había salido, se puede enviar dos veces.
 */
@Service
public class RecuperacionService {

    static final String ERROR_VENCIDA = "Procesamiento interrumpido: venció el plazo en PROCESANDO";

    private static final Logger log = LoggerFactory.getLogger(RecuperacionService.class);

    private final NotificacionRepository repositorio;
    private final PoliticaDeReintentos reintentos;
    private final Clock reloj;

    public RecuperacionService(NotificacionRepository repositorio, PoliticaDeReintentos reintentos, Clock reloj) {
        this.repositorio = repositorio;
        this.reintentos = reintentos;
        this.reloj = reloj;
    }

    /** Devuelve cuántas notificaciones recuperó (a PENDIENTE o a ERROR_FINAL). */
    public int recuperarVencidas() {
        Instant ahora = Instant.now(reloj);
        int maximo = reintentos.maximoIntentos();
        int finales = repositorio.pasarVencidasAErrorFinal(ahora, maximo, ERROR_VENCIDA);
        int devueltas = repositorio.devolverVencidasAPendiente(ahora, maximo, ERROR_VENCIDA);
        if (finales + devueltas > 0) {
            log.warn("Recuperación: {} notificaciones vencidas volvieron a PENDIENTE y {} pasaron a ERROR_FINAL",
                    devueltas, finales);
        }
        return finales + devueltas;
    }
}
