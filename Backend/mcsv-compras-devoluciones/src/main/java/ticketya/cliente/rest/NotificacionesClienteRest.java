package ticketya.cliente.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ticketya.cliente.NotificacionesCliente;

import java.time.Duration;
import java.util.Set;

/**
 * Contrato acordado: POST /internal/notificaciones con el encabezado X-Service-Token (el mismo
 * token compartido, SERVICIO_TOKEN). Responde 202 con el aviso nuevo o el duplicado.
 * 400, 415 y 422 son rechazos definitivos; cualquier otro error, o la falta de respuesta, es
 * temporal y se reintenta (un 403 suele ser un token mal configurado, que se corrige).
 */
@Component
public class NotificacionesClienteRest extends ClienteRest implements NotificacionesCliente {

    static final String ENCABEZADO_TOKEN = "X-Service-Token";
    private static final Set<Integer> RECHAZOS_DEFINITIVOS = Set.of(400, 415, 422);
    private static final Logger log = LoggerFactory.getLogger(NotificacionesClienteRest.class);

    private final String token;

    public NotificacionesClienteRest(@Value("${servicios.notificaciones.url}") String url,
                                     @Value("${compras.seguridad.token-servicio:}") String token,
                                     @Value("${servicios.timeout-conexion:2s}") Duration conexion,
                                     @Value("${servicios.timeout-lectura:5s}") Duration lectura) {
        super("Notificaciones", url, conexion, lectura);
        this.token = token == null ? "" : token;
        if (this.token.isBlank()) {
            log.warn("SERVICIO_TOKEN no está configurado: Notificaciones va a rechazar los avisos");
        }
    }

    @Override
    public void enviar(Aviso aviso) {
        llamar("enviar", () -> {
            http.post().uri("/internal/notificaciones")
                    .header(ENCABEZADO_TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(aviso)
                    .retrieve()
                    .toBodilessEntity();
            return Boolean.TRUE;
        }, (status, e) -> {
            if (RECHAZOS_DEFINITIVOS.contains(status.value())) {
                String codigo = codigoDeError(e);
                throw new AvisoRechazadoException("Notificaciones rechazó el aviso con " + status.value()
                        + (codigo != null ? " " + codigo : ""), e);
            }
            return null;
        });
    }
}
