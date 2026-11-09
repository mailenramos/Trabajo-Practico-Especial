package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.DisponibilidadEventoResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.EstadoVentaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.service.EstadoVentaService;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ReservaService;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ResultadoCreacionReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.service.DisponibilidadService;
import ar.edu.unicen.ms_disponibilidad_reservas.service.InicializacionDisponibilidadService;
import jakarta.validation.Valid;

/** REST endpoints for MS1 to initialize and query MS2's local inventory. */
@RestController
@RequestMapping("/disponibilidad/eventos")
public class DisponibilidadController {

    private final InicializacionDisponibilidadService inicializacionService;
    private final DisponibilidadService disponibilidadService;
    private final ReservaService reservaService;
    private final EstadoVentaService estadoVentaService;

    public DisponibilidadController(InicializacionDisponibilidadService inicializacionService,
            DisponibilidadService disponibilidadService,
            ReservaService reservaService,
            EstadoVentaService estadoVentaService) {
        this.inicializacionService = inicializacionService;
        this.disponibilidadService = disponibilidadService;
        this.reservaService = reservaService;
        this.estadoVentaService = estadoVentaService;
    }

    @PutMapping(path = "/{eventoId}", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> inicializar(
            @PathVariable String eventoId,
            @Valid @RequestBody InicializarDisponibilidadRequest request) {
        inicializacionService.inicializar(eventoId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/{eventoId}", produces = APPLICATION_JSON_VALUE)
    public DisponibilidadEventoResponse consultar(@PathVariable String eventoId) {
        return disponibilidadService.consultarDisponibilidadEvento(eventoId);
    }

    @PutMapping(path = "/{eventoId}/venta", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> actualizarEstadoVenta(
            @PathVariable String eventoId,
            @Valid @RequestBody EstadoVentaRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo de la solicitud es obligatorio.");
        }
        estadoVentaService.aplicar(eventoId, request.estado());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{eventoId}/reservas", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<ar.edu.unicen.ms_disponibilidad_reservas.dto.ReservaCreadaResponse> crearReserva(
            @PathVariable String eventoId,
            @RequestHeader("X-User-Id") String usuarioId,
            @Valid @RequestBody CrearReservaRequest request) {
        ResultadoCreacionReserva resultado = reservaService.crear(eventoId, usuarioId, request);
        return ResponseEntity.status(resultado.status()).body(resultado.reserva());
    }

}
