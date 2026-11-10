package ticketya.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.servicio.CancelacionService;
import ticketya.servicio.DevolucionService;
import ticketya.servicio.ReintegroService;
import ticketya.web.dto.ReintegroRespuesta;

import java.util.List;
import java.util.UUID;

/** Reintegros pedidos a Pagos. Solo para administradores. */
@RestController
@RequestMapping("/reintegros")
public class ReintegroController {

    private final ReintegroService reintegroService;
    private final DevolucionService devolucionService;
    private final CancelacionService cancelacionService;
    private final Permisos permisos;

    public ReintegroController(ReintegroService reintegroService, DevolucionService devolucionService,
                               CancelacionService cancelacionService, Permisos permisos) {
        this.reintegroService = reintegroService;
        this.devolucionService = devolucionService;
        this.cancelacionService = cancelacionService;
        this.permisos = permisos;
    }

    /** Los reintegros, opcionalmente de un estado (para encontrar los que hay que reintentar). */
    @GetMapping
    public List<ReintegroRespuesta> listar(UsuarioActual usuario,
                                           @RequestParam(required = false) EstadoReintegro estado) {
        permisos.exigirAdministrador(usuario);
        return reintegroService.listar(estado).stream().map(ReintegroRespuesta::de).toList();
    }

    /**
     * Reintenta sin duplicar. El reintegro de una devolución retoma la devolución completa
     * desde el paso que falló; el de una cancelación, además, pasa a devuelta sus entradas.
     * 200 si quedó exitoso (y, en una devolución o cancelación, completo); 202 si algo sigue
     * pendiente.
     */
    @PostMapping("/{reintegroId}/reintento")
    public ResponseEntity<ReintegroRespuesta> reintentar(UsuarioActual usuario, @PathVariable UUID reintegroId) {
        permisos.exigirAdministrador(usuario);
        Reintegro r = reintegroService.obtener(reintegroId);
        boolean completo;
        if (r.getOrigen() == OrigenReintegro.DEVOLUCION) {
            completo = devolucionService.coordinar(r.getRefundRequestId()).coordinacionCompleta();
            r = reintegroService.obtener(reintegroId);
        } else if (r.getOrigen() == OrigenReintegro.CANCELACION_EVENTO) {
            // Además del reintegro, pasa a devuelta las entradas que incluye
            completo = cancelacionService.reintentar(reintegroId);
            r = reintegroService.obtener(reintegroId);
        } else {
            r = reintegroService.reintentar(reintegroId);
            completo = true;
        }
        boolean exitoso = completo && r.getEstado() == EstadoReintegro.EXITOSO;
        return ResponseEntity.status(exitoso ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ReintegroRespuesta.de(r));
    }
}
