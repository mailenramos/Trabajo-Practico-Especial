package com.ticketya.gestionlugares.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.service.EventoService;

@RestController
@RequestMapping("/eventos")
@Tag(name = "Eventos", description = "Creación en borrador y configuración comercial local de eventos.")
public class EventoController {

    private final EventoService eventoService;

    public EventoController(EventoService eventoService) {
        this.eventoService = eventoService;
    }

    @PostMapping
    @Operation(summary = "Crear evento en borrador")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evento creado en borrador",
                    content = @Content(schema = @Schema(implementation = Evento.class))),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-11-07T00:00:00Z","status":400,"error":"Bad Request","mensaje":"La fecha y hora de inicio debe ser futura","campos":[{"campo":"nombre","mensaje":"no debe estar vacío"}]}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Falta identidad",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "El rol no es organizador",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "El lugar no existe",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Evento> crear(
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(allowableValues = {"organizador"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @Valid @RequestBody CreateEventoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventoService.crearEvento(request, userId, userRole));
    }

    @PutMapping("/{eventoId}/sectores/{sectorId}/precio")
    @Operation(summary = "Definir el precio de un sector del evento")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Precio asignado"),
            @ApiResponse(responseCode = "400", description = "Precio menor que cero"),
            @ApiResponse(responseCode = "401", description = "Falta identidad"),
            @ApiResponse(responseCode = "403", description = "No es el organizador dueño"),
            @ApiResponse(responseCode = "404", description = "Evento o sector no encontrado"),
            @ApiResponse(responseCode = "409", description = "El evento ya no está en borrador")
    })
    public Evento asignarPrecio(
            @PathVariable String eventoId,
            @PathVariable String sectorId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @Valid @RequestBody ActualizarPrecioRequest request) {
        return eventoService.asignarPrecio(eventoId, sectorId, request.precio(), userId, userRole);
    }

    @PutMapping("/{eventoId}/limite-entradas-por-cliente")
    @Operation(summary = "Actualizar límite de entradas por cliente durante el borrador")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Límite actualizado"),
            @ApiResponse(responseCode = "400", description = "Límite menor que uno"),
            @ApiResponse(responseCode = "401", description = "Falta identidad"),
            @ApiResponse(responseCode = "403", description = "No es el organizador dueño"),
            @ApiResponse(responseCode = "404", description = "Evento no encontrado"),
            @ApiResponse(responseCode = "409", description = "El evento ya no está en borrador")
    })
    public Evento actualizarLimite(
            @PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @Valid @RequestBody ActualizarLimiteEntradasRequest request) {
        return eventoService.actualizarLimiteEntradas(eventoId,
                request.limiteEntradasPorCliente(), userId, userRole);
    }
}
