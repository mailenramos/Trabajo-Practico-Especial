package ticketya.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ticketya.modelo.EstadoSolicitud;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketya.servicio.DevolucionService;
import ticketya.servicio.DevolucionService.InicioDevolucion;
import ticketya.web.dto.DevolucionRespuesta;
import ticketya.web.dto.RechazarDevolucionPedido;
import ticketya.web.dto.SolicitarDevolucionPedido;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/devoluciones")
public class DevolucionController {

    private final DevolucionService devolucionService;
    private final Permisos permisos;

    public DevolucionController(DevolucionService devolucionService, Permisos permisos) {
        this.devolucionService = devolucionService;
        this.permisos = permisos;
    }

    /**
     * Solicita la devolución de una entrada propia y vigente, hasta 72 horas antes del
     * evento. 201 con la solicitud nueva; 200 si ya había una pendiente para la entrada.
     */
    @PostMapping
    public ResponseEntity<DevolucionRespuesta> solicitar(UsuarioActual usuario,
                                                         @Valid @RequestBody SolicitarDevolucionPedido pedido) {
        InicioDevolucion inicio = devolucionService.solicitar(usuario, pedido.entradaId());
        return ResponseEntity.status(inicio.nueva() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(devolucionService.respuesta(inicio.solicitud()));
    }

    /** El cliente ve solo sus solicitudes; el administrador, todas. Filtro opcional por estado. */
    @GetMapping
    public List<DevolucionRespuesta> listar(UsuarioActual usuario,
                                            @RequestParam(required = false) EstadoSolicitud estado) {
        return devolucionService.listar(usuario, permisos.esAdministrador(usuario), estado);
    }

    /** Una solicitud con el estado de su reintegro. La de otro cliente da 403. */
    @GetMapping("/{devolucionId}")
    public DevolucionRespuesta obtener(UsuarioActual usuario, @PathVariable UUID devolucionId) {
        return devolucionService.obtener(usuario, permisos.esAdministrador(usuario), devolucionId);
    }

    /**
     * Aprueba una solicitud pendiente y coordina la devolución. 200 si quedó completa; 202
     * si algún paso quedó pendiente (se retoma con POST /reintegros/{id}/reintento).
     */
    @PostMapping("/{devolucionId}/aprobacion")
    public ResponseEntity<DevolucionRespuesta> aprobar(UsuarioActual usuario, @PathVariable UUID devolucionId) {
        permisos.exigirAdministrador(usuario);
        DevolucionRespuesta r = devolucionService.respuesta(devolucionService.aprobar(usuario, devolucionId));
        return ResponseEntity.status(r.coordinacion() != null && r.coordinacion().completa()
                ? HttpStatus.OK : HttpStatus.ACCEPTED).body(r);
    }

    /** Rechaza una solicitud pendiente con un motivo. Solo administrador. */
    @PostMapping("/{devolucionId}/rechazo")
    public DevolucionRespuesta rechazar(UsuarioActual usuario, @PathVariable UUID devolucionId,
                                        @Valid @RequestBody RechazarDevolucionPedido pedido) {
        permisos.exigirAdministrador(usuario);
        return devolucionService.respuesta(devolucionService.rechazar(usuario, devolucionId, pedido.motivo()));
    }
}
