package ar.edu.unicen.ms_fila_virtual.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ar.edu.unicen.ms_fila_virtual.dto.ConsumirTurnoRequest;
import ar.edu.unicen.ms_fila_virtual.dto.ConsumoTurnoResponse;
import ar.edu.unicen.ms_fila_virtual.service.FilaVirtualService;
import jakarta.validation.Valid;

/** Internal routes must only be reachable from the private service network. */
@RestController
@RequestMapping("/internal/filas/eventos")
public class FilaInternaController {
    private final FilaVirtualService servicio;

    public FilaInternaController(FilaVirtualService servicio) { this.servicio = servicio; }

    @PostMapping(path = "/{eventoId}/consumos", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public ConsumoTurnoResponse consumir(@PathVariable String eventoId, @Valid @RequestBody ConsumirTurnoRequest request) {
        return servicio.consumir(eventoId, request);
    }

    @PutMapping(path = "/{eventoId}/cancelacion")
    public ResponseEntity<Void> cancelar(@PathVariable String eventoId) {
        servicio.cancelar(eventoId);
        return ResponseEntity.noContent().build();
    }
}
