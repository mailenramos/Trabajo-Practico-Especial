package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketya.cliente.NotificacionesCliente;
import ticketya.cliente.NotificacionesCliente.Aviso;
import ticketya.cliente.NotificacionesCliente.AvisoRechazadoException;
import ticketya.modelo.AvisoPendiente;
import ticketya.repositorio.AvisoPendienteRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Envía a Notificaciones los avisos guardados en el outbox. Cada aviso se toma con un cambio
 * condicional de PENDIENTE a PROCESANDO (solo una ejecución lo toma) y se envía fuera de toda
 * transacción de negocio: una falla de Notificaciones nunca toca compras, devoluciones ni
 * reintegros.
 * <p>
 * Resultados: 2xx deja el aviso ENVIADO; un rechazo definitivo (400) lo deja en ERROR_FINAL; una
 * falla temporal lo vuelve a PENDIENTE con espera creciente (base, 2 × base, 4 × base..., con un
 * tope) hasta el máximo de intentos, y entonces queda en ERROR_FINAL con el último error.
 * El envío es de al menos una vez: Notificaciones deduplica por acontecimiento, tipo y destinatario.
 */
@Service
public class EnvioDeAvisosService {

    private static final Logger log = LoggerFactory.getLogger(EnvioDeAvisosService.class);

    private final AvisoPendienteRepository avisos;
    private final NotificacionesCliente notificaciones;
    private final JsonMapper json;
    private final int intentosMaximos;
    private final Duration esperaBase;
    private final Duration esperaMaxima;
    private final Duration plazoProcesando;
    private final int lote;

    public EnvioDeAvisosService(AvisoPendienteRepository avisos, NotificacionesCliente notificaciones, JsonMapper json,
                                @Value("${compras.avisos.intentos-maximos:5}") int intentosMaximos,
                                @Value("${compras.avisos.espera-base:30s}") Duration esperaBase,
                                @Value("${compras.avisos.espera-maxima:30m}") Duration esperaMaxima,
                                @Value("${compras.avisos.plazo-procesando:2m}") Duration plazoProcesando,
                                @Value("${compras.avisos.lote:50}") int lote) {
        if (intentosMaximos < 1) {
            throw new IllegalArgumentException("compras.avisos.intentos-maximos debe ser al menos 1");
        }
        this.avisos = avisos;
        this.notificaciones = notificaciones;
        this.json = json;
        this.intentosMaximos = intentosMaximos;
        this.esperaBase = esperaBase;
        this.esperaMaxima = esperaMaxima;
        this.plazoProcesando = plazoProcesando;
        this.lote = lote;
    }

    /**
     * Envía los avisos pendientes cuyo próximo intento ya llegó (hasta un lote por llamada).
     *
     * @return cuántos quedaron ENVIADOS en esta llamada
     */
    public int enviarPendientes() {
        Instant ahora = Instant.now();
        List<Long> ids = avisos.idsListosParaEnviar(ahora, Limit.of(lote));
        int enviados = 0;
        for (Long id : ids) {
            try {
                if (enviar(id, ahora)) {
                    enviados++;
                }
            } catch (RuntimeException e) {
                // Un error de la base con un aviso no frena a los demás; si quedó PROCESANDO, vence y se recupera
                log.error("No se pudo procesar el aviso {}", id, e);
            }
        }
        return enviados;
    }

    /**
     * Recupera los avisos que quedaron PROCESANDO más allá de su vencimiento (el servicio se cayó o
     * el envío se colgó a mitad de camino): cuentan el intento interrumpido y vuelven a PENDIENTE,
     * o pasan a ERROR_FINAL si ya agotaron los intentos.
     *
     * @return cuántos avisos se recuperaron
     */
    @Transactional
    public int recuperarVencidos() {
        Instant ahora = Instant.now();
        String error = "Envío interrumpido: el aviso quedó PROCESANDO más de " + plazoProcesando;
        int finales = avisos.recuperarVencidosComoErrorFinal(ahora, intentosMaximos, error);
        int pendientes = avisos.recuperarVencidosComoPendientes(ahora, error);
        if (finales + pendientes > 0) {
            log.warn("Se recuperaron {} avisos interrumpidos: {} vuelven a pendiente y {} quedan en ERROR_FINAL",
                    finales + pendientes, pendientes, finales);
        }
        return finales + pendientes;
    }

    private boolean enviar(Long id, Instant ahora) {
        String toma = UUID.randomUUID().toString();
        if (avisos.tomar(id, toma, ahora, ahora.plus(plazoProcesando)) != 1) {
            return false; // otra ejecución lo tomó primero
        }
        AvisoPendiente a = avisos.findById(id).orElseThrow();
        int intentos = a.getIntentos() + 1;
        try {
            notificaciones.enviar(aviso(a));
        } catch (AvisoRechazadoException e) {
            log.error("Notificaciones rechazó el aviso {} ({} {}): no se reintenta", id, a.getTipo(),
                    a.getAcontecimientoId(), e);
            guardar(avisos.marcarErrorFinal(id, toma, intentos, AvisoPendiente.recortar(e.getMessage())), id);
            return false;
        } catch (RuntimeException e) {
            fallaTemporal(a, toma, intentos, e.getMessage());
            return false;
        }
        return guardar(avisos.marcarEnviado(id, toma, Instant.now()), id);
    }

    private void fallaTemporal(AvisoPendiente a, String toma, int intentos, String error) {
        String recortado = AvisoPendiente.recortar(error);
        if (intentos >= intentosMaximos) {
            log.error("Aviso {} ({} {}) sin enviar tras {} intentos: queda en ERROR_FINAL. Último error: {}",
                    a.getId(), a.getTipo(), a.getAcontecimientoId(), intentos, error);
            guardar(avisos.marcarErrorFinal(a.getId(), toma, intentos, recortado), a.getId());
            return;
        }
        Instant proximo = Instant.now().plus(espera(intentos));
        log.warn("Aviso {} ({}) no enviado (intento {} de {}), se reintenta desde {}: {}",
                a.getId(), a.getTipo(), intentos, intentosMaximos, proximo, error);
        guardar(avisos.reprogramar(a.getId(), toma, intentos, proximo, recortado), a.getId());
    }

    /**
     * El resultado se guarda solo si el aviso sigue tomado por esta ejecución. Si la recuperación
     * lo liberó por vencido mientras se enviaba, no se pisa su estado (puede repetirse el envío,
     * y Notificaciones lo deduplica).
     */
    private boolean guardar(int filas, Long id) {
        if (filas == 0) {
            log.warn("El aviso {} dejó de estar tomado por este envío (venció y se recuperó): no se guarda el resultado", id);
            return false;
        }
        return true;
    }

    /** Espera antes del próximo intento: base × 2^(intentos − 1), como mucho la espera máxima. */
    Duration espera(int intentos) {
        Duration espera = esperaBase;
        for (int i = 1; i < intentos && espera.compareTo(esperaMaxima) < 0; i++) {
            espera = espera.multipliedBy(2);
        }
        return espera.compareTo(esperaMaxima) > 0 ? esperaMaxima : espera;
    }

    private Aviso aviso(AvisoPendiente a) {
        return new Aviso(a.getAcontecimientoId(), a.getTipo().name(), NotificacionesCliente.VERSION_ESQUEMA,
                a.getCreadoEn(), a.getRecursoId(), a.getUsuarioId(), a.getCorrelacionId(), json.readTree(a.getDatos()));
    }
}
