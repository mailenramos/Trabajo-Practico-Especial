package ticketya.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ticketya.servicio.CancelacionService;
import ticketya.servicio.CancelacionService.ResultadoAviso;
import ticketya.web.dto.CancelacionEventoPedido;

/**
 * Aviso de cancelación de un evento (E-1). Lo llama el servicio de eventos de forma
 * directa, sin el Gateway ni encabezados de usuario. Idempotente por eventoId.
 */
@RestController
public class CancelacionController {

    private final CancelacionService cancelacionService;

    public CancelacionController(CancelacionService cancelacionService) {
        this.cancelacionService = cancelacionService;
    }

    public record AvisoRespuesta(String eventoId, ResultadoAviso resultado) {
    }

    /** 202 si el aviso es nuevo o la cancelación sigue en proceso; 200 si ya se procesó. */
    @PostMapping("/eventos-cancelados")
    public ResponseEntity<AvisoRespuesta> recibir(@Valid @RequestBody CancelacionEventoPedido pedido) {
        String eventoId = pedido.eventoId().trim();
        ResultadoAviso resultado = cancelacionService.recibir(eventoId, pedido.motivo(), pedido.canceladoEn());
        HttpStatus status = resultado == ResultadoAviso.YA_PROCESADO ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(new AvisoRespuesta(eventoId, resultado));
    }
}
