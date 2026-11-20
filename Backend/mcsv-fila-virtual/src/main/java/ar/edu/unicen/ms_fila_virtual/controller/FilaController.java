package ar.edu.unicen.ms_fila_virtual.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ar.edu.unicen.ms_fila_virtual.dto.ConfiguracionFilaRequest;
import ar.edu.unicen.ms_fila_virtual.dto.EstadoFilaRequest;
import ar.edu.unicen.ms_fila_virtual.dto.FilaEventoResponse;
import ar.edu.unicen.ms_fila_virtual.dto.TurnoResponse;
import ar.edu.unicen.ms_fila_virtual.service.FilaVirtualService;
import ar.edu.unicen.ms_fila_virtual.service.FilaCoordinacionService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/filas/eventos")
public class FilaController {
    private final FilaVirtualService servicio;
    private final FilaCoordinacionService coordinacion;

    public FilaController(FilaVirtualService servicio, FilaCoordinacionService coordinacion) {
        this.servicio = servicio;
        this.coordinacion = coordinacion;
    }

    @PostMapping(path = "/{eventoId}/ingresos", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<TurnoResponse> ingresar(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String usuario,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        IdentidadActual identidad = IdentidadActual.desde(usuario, roles);
        identidad.exigirRol("CLIENTE");
        coordinacion.verificarIngreso(eventoId);
        var resultado = servicio.ingresar(eventoId, identidad.usuarioId());
        return ResponseEntity.status(resultado.creado() ? HttpStatus.CREATED : HttpStatus.OK).body(resultado.turno());
    }

    @GetMapping(path = "/{eventoId}/mi-turno", produces = APPLICATION_JSON_VALUE)
    public TurnoResponse miTurno(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String usuario,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        IdentidadActual identidad = IdentidadActual.desde(usuario, roles);
        identidad.exigirRol("CLIENTE");
        return servicio.consultarTurno(eventoId, identidad.usuarioId());
    }

    @GetMapping(path = "/{eventoId}", produces = APPLICATION_JSON_VALUE)
    public FilaEventoResponse consultar(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String usuario,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        IdentidadActual identidad = IdentidadActual.desde(usuario, roles);
        identidad.exigirRol("ADMIN", "ORGANIZADOR");
        return coordinacion.consultarFila(eventoId, identidad.usuarioId(), identidad.roles().contains("ADMIN"));
    }

    @PutMapping(path = "/{eventoId}/configuracion", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> configurar(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String usuario,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody ConfiguracionFilaRequest request) {
        IdentidadActual identidad = IdentidadActual.desde(usuario, roles);
        identidad.exigirRol("ADMIN", "ORGANIZADOR");
        coordinacion.configurar(eventoId, identidad.usuarioId(), identidad.roles().contains("ADMIN"), request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(path = "/{eventoId}/estado", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cambiarEstado(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String usuario,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody EstadoFilaRequest request) {
        IdentidadActual identidad = IdentidadActual.desde(usuario, roles);
        identidad.exigirRol("ADMIN", "ORGANIZADOR");
        coordinacion.cambiarEstado(eventoId, identidad.usuarioId(), identidad.roles().contains("ADMIN"), request.estado());
        return ResponseEntity.noContent().build();
    }

}
