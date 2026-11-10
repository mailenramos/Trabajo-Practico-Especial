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
import com.ticketya.gestionlugares.service.EventoLifecycleService;
import com.ticketya.gestionlugares.service.EventoService;

@RestController
@RequestMapping("/eventos")
@Tag(name = "Eventos", description = "Ciclo de vida completo: creación en borrador, precios, límite por cliente, publicación con inicialización en Reservas, suspensión y reactivación de venta, cancelación y callback interno de disponibilidad.")
public class EventoController {

    private final EventoService eventoService;
    private final EventoLifecycleService eventoLifecycleService;

    public EventoController(EventoService eventoService, EventoLifecycleService eventoLifecycleService) {
        this.eventoService = eventoService;
        this.eventoLifecycleService = eventoLifecycleService;
    }

    @PostMapping
    @Operation(summary = "Crear evento en borrador", description = "Requiere X-User-Id y X-User-Role: ORGANIZADOR.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evento creado en borrador",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class),
                            examples = @ExampleObject(name = "Evento en borrador", value = ApiExamples.EVENTO_NUEVO))),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ApiExamples.ERROR))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "El rol no es organizador", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "El lugar no existe", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Conflicto de persistencia", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Evento> crear(
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(allowableValues = {"ORGANIZADOR"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Datos del evento; limiteEntradasPorCliente es opcional y vale 4 si se omite.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateEventoRequest.class),
                            examples = @ExampleObject(name = "Evento en borrador", value = """
                                    {"nombre":"Festival Central","descripcion":"Música en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","lugarId":"507f1f77bcf86cd799439011","limiteEntradasPorCliente":4}
                                    """)))
            @Valid @RequestBody CreateEventoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventoService.crearEvento(request, userId, userRole));
    }

    @PutMapping("/{eventoId}/sectores/{sectorId}/precio")
    @Operation(summary = "Definir el precio de un sector del evento", description = "Requiere X-User-Id y X-User-Role: ORGANIZADOR; el usuario debe ser dueño del evento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Precio asignado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class),
                            examples = @ExampleObject(name = "Evento con precio", value = ApiExamples.EVENTO_BORRADOR))),
            @ApiResponse(responseCode = "400", description = "Precio menor que cero o body inválido", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ApiExamples.ERROR))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "No es el organizador dueño", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento o sector no encontrado", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "El evento ya no está en borrador o hubo conflicto de versión", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public Evento asignarPrecio(
            @PathVariable String eventoId,
            @PathVariable String sectorId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Precio decimal mayor o igual a cero.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActualizarPrecioRequest.class),
                            examples = @ExampleObject(name = "Precio", value = "{\"precio\":15000.00}")))
            @Valid @RequestBody ActualizarPrecioRequest request) {
        return eventoService.asignarPrecio(eventoId, sectorId, request.precio(), userId, userRole);
    }

    @PutMapping("/{eventoId}/limite-entradas-por-cliente")
    @Operation(summary = "Actualizar límite de entradas por cliente durante el borrador", description = "Requiere X-User-Id y X-User-Role: ORGANIZADOR; el usuario debe ser dueño del evento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Límite actualizado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class),
                            examples = @ExampleObject(name = "Evento con límite", value = ApiExamples.EVENTO_CON_LIMITE))),
            @ApiResponse(responseCode = "400", description = "Límite inválido o body inválido", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ApiExamples.ERROR))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "No es el organizador dueño", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento no encontrado", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "El evento ya no está en borrador o hubo conflicto de versión", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public Evento actualizarLimite(
            @PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Límite entero mayor o igual a uno; no se acepta null.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActualizarLimiteEntradasRequest.class),
                            examples = @ExampleObject(name = "Límite", value = "{\"limiteEntradasPorCliente\":8}")))
            @Valid @RequestBody ActualizarLimiteEntradasRequest request) {
        return eventoService.actualizarLimiteEntradas(eventoId,
                request.limiteEntradasPorCliente(), userId, userRole);
    }

    @PutMapping("/{eventoId}/publicar")
    @Operation(summary = "Publicar evento e inicializar su disponibilidad y venta en Reservas", description = "Requiere X-User-Id y X-User-Role: ORGANIZADOR; el usuario debe ser dueño del evento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evento publicado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class),
                            examples = @ExampleObject(name = "Evento publicado", value = ApiExamples.EVENTO_PUBLICADO))),
            @ApiResponse(responseCode = "400", description = "Faltan precios por sector", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "No es el organizador dueño", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Estado o versión incompatible", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Reservas no confirmó la inicialización", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public Evento publicar(
            @PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        return eventoLifecycleService.publicar(eventoId, userId, userRole);
    }

    @PutMapping("/{eventoId}/venta/suspender")
    @Operation(summary = "Suspender la venta del evento", description = "Requiere X-User-Id y X-User-Role: ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Venta suspendida", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class), examples = @ExampleObject(name = "Venta suspendida", value = ApiExamples.EVENTO_VENTA_SUSPENDIDA))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ADMIN", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Estado o versión incompatible", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Reservas no pudo cambiar el estado de venta", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public Evento suspenderVenta(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        return eventoLifecycleService.suspenderVenta(eventoId, userId, userRole);
    }

    @PutMapping("/{eventoId}/venta/reactivar")
    @Operation(summary = "Reactivar la venta del evento", description = "Requiere X-User-Id y X-User-Role: ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Venta reactivada", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class), examples = @ExampleObject(name = "Venta activa", value = ApiExamples.EVENTO_PUBLICADO))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ADMIN", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Estado o versión incompatible", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Reservas no pudo cambiar el estado de venta", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public Evento reactivarVenta(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        return eventoLifecycleService.reactivarVenta(eventoId, userId, userRole);
    }

    @PutMapping("/{eventoId}/cancelar")
    @Operation(summary = "Cancelar evento y detener su venta", description = "Requiere X-User-Id y X-User-Role: ADMIN. El body y el motivo son opcionales; si se omiten, se aplica el motivo predeterminado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evento cancelado", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Evento.class), examples = @ExampleObject(name = "Evento cancelado", value = ApiExamples.EVENTO_CANCELADO))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ADMIN", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Estado o versión incompatible", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Reservas no pudo cancelar la venta", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public Evento cancelar(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false,
                    description = "Motivo opcional de cancelación; si se omite, se usa el motivo predeterminado.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CancelarEventoRequest.class),
                            examples = @ExampleObject(name = "Motivo", value = "{\"motivo\":\"Cancelación por condiciones climáticas\"}")))
            @RequestBody(required = false) CancelarEventoRequest request) {
        return eventoLifecycleService.cancelar(eventoId, request == null ? null : request.motivo(), userId, userRole);
    }

    @PutMapping("/{eventoId}/disponibilidad")
    @Operation(summary = "Callback interno de disponibilidad desde Reservas",
            description = "Ruta interna invocada directamente por Reservas. No exige X-User-Id ni X-User-Role y no se expone por el API Gateway.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Aviso aplicado, duplicado o tardío"),
            @ApiResponse(responseCode = "400", description = "Body inválido", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "El evento está en borrador", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> aplicarDisponibilidad(@PathVariable String eventoId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Indica si el evento se agotó o recuperó disponibilidad.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DisponibilidadEventoRequest.class),
                            examples = @ExampleObject(name = "Agotado", value = "{\"agotado\":true}")))
            @Valid @RequestBody DisponibilidadEventoRequest request) {
        eventoLifecycleService.aplicarDisponibilidad(eventoId, request.agotado());
        return ResponseEntity.noContent().build();
    }
}
