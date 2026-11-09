package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.ReservaConsultaResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ReservaService;
import ar.edu.unicen.ms_disponibilidad_reservas.service.DevolucionService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/disponibilidad/reservas")
public class ReservaController {

    private final ReservaService reservaService;
    private final DevolucionService devolucionService;

    public ReservaController(ReservaService reservaService, DevolucionService devolucionService) {
        this.reservaService = reservaService;
        this.devolucionService = devolucionService;
    }

    @GetMapping(path = "/{reservaId}", produces = APPLICATION_JSON_VALUE)
    public ReservaConsultaResponse consultar(@PathVariable String reservaId) {
        return reservaService.consultar(reservaId);
    }

    @PostMapping(path = "/{reservaId}/confirmacion")
    public ResponseEntity<Void> confirmar(@PathVariable String reservaId) {
        reservaService.confirmar(reservaId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{reservaId}/liberacion", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> liberar(
            @PathVariable String reservaId,
            @Valid @RequestBody LiberarReservaRequest request) {
        if ("DEVOLUCION_APROBADA".equals(request.motivo())) {
            devolucionService.devolverAprobada(reservaId, request);
        } else {
            reservaService.liberarPorPagoRechazado(reservaId);
        }
        return ResponseEntity.noContent().build();
    }
}
