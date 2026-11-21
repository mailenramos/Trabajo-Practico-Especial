package ticketya.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ticketya.error.ValidacionException;
import ticketya.servicio.ModificacionEventoService;
import ticketya.servicio.ModificacionEventoService.ResultadoModificacion;
import ticketya.web.dto.ModificacionEventoPedido;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aviso de modificación de un evento (fecha, horario o lugar). Lo llama Eventos de forma directa,
 * sin el Gateway; exige X-Service-Token ({@link AutorizacionDeServicioFilter}). Idempotente por
 * eventoId + version.
 */
@RestController
public class ModificacionEventoController {

    private final ModificacionEventoService modificaciones;

    public ModificacionEventoController(ModificacionEventoService modificaciones) {
        this.modificaciones = modificaciones;
    }

    public record ModificacionRespuesta(String eventoId, int version, ResultadoModificacion resultado) {
    }

    /** 202 si la modificación es nueva; 200 si esa versión ya se procesó o es anterior a la procesada. */
    @PostMapping("/internal/eventos-modificados")
    public ResponseEntity<ModificacionRespuesta> recibir(@Valid @RequestBody ModificacionEventoPedido pedido) {
        String eventoId = pedido.eventoId().trim();
        String lugar = texto(pedido.lugar());
        String ciudad = texto(pedido.ciudad());
        Map<String, String> campos = new LinkedHashMap<>();
        Instant fechaHora = null;
        if (texto(pedido.fechaHora()) != null) {
            try {
                fechaHora = Instant.parse(pedido.fechaHora().trim());
            } catch (DateTimeException e) {
                campos.put("fechaHora", "debe ser una fecha y hora ISO-8601 en UTC, por ejemplo 2026-12-15T23:30:00Z");
            }
        } else if (lugar == null && ciudad == null) {
            campos.put("fechaHora", "debe venir al menos uno de fechaHora, lugar o ciudad");
        }
        if (!campos.isEmpty()) {
            throw new ValidacionException(campos);
        }

        ResultadoModificacion resultado = modificaciones.recibir(eventoId, pedido.version(), fechaHora, lugar, ciudad);
        HttpStatus status = resultado == ResultadoModificacion.ACEPTADA ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new ModificacionRespuesta(eventoId, pedido.version(), resultado));
    }

    /** Texto sin espacios de más; vacío cuenta como ausente. */
    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
