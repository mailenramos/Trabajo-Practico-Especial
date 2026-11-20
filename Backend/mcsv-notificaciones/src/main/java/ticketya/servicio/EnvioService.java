package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ticketya.cliente.UsuariosCliente;
import ticketya.config.NotificacionesProperties;
import ticketya.envio.EnvioDeCorreo;
import ticketya.envio.FalloDefinitivoException;
import ticketya.envio.FalloTemporalException;
import ticketya.envio.Plantillas;
import ticketya.modelo.Notificacion;
import ticketya.repositorio.NotificacionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Envío de las notificaciones PENDIENTE cuyo próximo intento ya llegó. Cada una se toma con
 * una actualización condicional (PENDIENTE a PROCESANDO), así dos instancias nunca procesan
 * la misma. Las llamadas a Gestión de usuarios y SMTP se hacen fuera de toda transacción.
 */
@Service
public class EnvioService {

    private static final Logger log = LoggerFactory.getLogger(EnvioService.class);

    private final NotificacionRepository repositorio;
    private final UsuariosCliente usuarios;
    private final Plantillas plantillas;
    private final EnvioDeCorreo envio;
    private final PoliticaDeReintentos reintentos;
    private final NotificacionesProperties propiedades;
    private final Clock reloj;

    public EnvioService(NotificacionRepository repositorio, UsuariosCliente usuarios, Plantillas plantillas,
                        EnvioDeCorreo envio, PoliticaDeReintentos reintentos, NotificacionesProperties propiedades,
                        Clock reloj) {
        this.repositorio = repositorio;
        this.usuarios = usuarios;
        this.plantillas = plantillas;
        this.envio = envio;
        this.reintentos = reintentos;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    /** Procesa un lote de pendientes. Devuelve cuántas tomó esta instancia. */
    public int procesarPendientes() {
        Instant ahora = Instant.now(reloj);
        List<UUID> candidatas = repositorio.idsListosParaEnviar(ahora,
                PageRequest.of(0, propiedades.envio().tamanioLote()));
        Instant vence = ahora.plus(propiedades.envio().plazoProcesamiento());
        int tomadas = 0;
        for (UUID id : candidatas) {
            if (repositorio.tomar(id, vence) == 1) {
                tomadas++;
                repositorio.findById(id).ifPresent(this::procesar);
            }
        }
        return tomadas;
    }

    /**
     * La notificación ya está en PROCESANDO y la tomó esta instancia ({@code n} se leyó después de
     * la toma, así que su vencimiento identifica esa toma). El resultado se guarda con una
     * actualización condicional: si mientras tanto la recuperación la liberó u otra instancia la
     * volvió a tomar, no se pisa nada.
     */
    void procesar(Notificacion n) {
        Instant venceToma = n.getVenceProcesamiento();
        try {
            String destinatario = usuarios.correoDe(n.getUsuarioId());
            envio.enviar(plantillas.generar(n, destinatario));
            if (guardado(n, "ENVIADA", repositorio.guardarEnviada(n.getId(), venceToma, Instant.now(reloj)))) {
                log.info("Notificación {} enviada (tipo {}, correlación {})", n.getId(), n.getTipo(),
                        n.getCorrelacionId());
            }
        } catch (FalloDefinitivoException e) {
            if (guardado(n, "ERROR_FINAL",
                    repositorio.guardarErrorFinal(n.getId(), venceToma, Notificacion.recortar(e.getMessage())))) {
                log.warn("Notificación {} en ERROR_FINAL: {}", n.getId(), e.getMessage());
            }
        } catch (FalloTemporalException e) {
            registrarFalloTemporal(n, venceToma, e.getMessage());
        } catch (RuntimeException e) {
            // Error no previsto: se trata como temporal para que los reintentos sigan siendo limitados
            log.error("Error inesperado al enviar la notificación {}", n.getId(), e);
            registrarFalloTemporal(n, venceToma, "Error inesperado: " + e);
        }
    }

    private void registrarFalloTemporal(Notificacion n, Instant venceToma, String error) {
        int intentosHechos = n.getIntentos() + 1;
        String ultimoError = Notificacion.recortar(error);
        if (reintentos.agotados(intentosHechos)) {
            if (guardado(n, "ERROR_FINAL", repositorio.guardarErrorFinal(n.getId(), venceToma, ultimoError))) {
                log.warn("Notificación {} en ERROR_FINAL tras {} intentos: {}", n.getId(), intentosHechos, error);
            }
            return;
        }
        Instant proximo = Instant.now(reloj).plus(reintentos.esperaTras(intentosHechos));
        if (guardado(n, "PENDIENTE", repositorio.guardarReintento(n.getId(), venceToma, proximo, ultimoError))) {
            log.info("Notificación {} pospuesta hasta {} (intento {} de {}): {}", n.getId(), proximo,
                    intentosHechos, reintentos.maximoIntentos(), error);
        }
    }

    /** 0 filas: la notificación ya no está en esta toma; se deja como está y se sigue. */
    private static boolean guardado(Notificacion n, String resultado, int filas) {
        if (filas == 0) {
            log.warn("No se guardó el resultado {} de la notificación {}: ya no está en PROCESANDO con esta toma "
                    + "(la recuperó la tarea de recuperación o la tomó otra instancia). No se pisa su estado actual",
                    resultado, n.getId());
            return false;
        }
        return true;
    }
}
