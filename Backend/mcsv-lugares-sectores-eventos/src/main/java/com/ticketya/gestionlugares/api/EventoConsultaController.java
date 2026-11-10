package com.ticketya.gestionlugares.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.service.EventoConsultaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/eventos")
@Tag(name = "Consulta de eventos", description = "Búsqueda paginada y detalle público de eventos; los tres roles autenticados pueden consultar.")
public class EventoConsultaController {
    private static final String ERROR = "application/json";

    private final EventoConsultaService consultaService;

    public EventoConsultaController(EventoConsultaService consultaService) {
        this.consultaService = consultaService;
    }

    @GetMapping
    @Operation(summary = "Buscar eventos publicados y agotados", description = "Combina filtros con AND; por defecto ordena por fecha ascendente, página 0 y tamaño 20. El tamaño máximo es 100. Fechas inclusivas en ISO-8601 UTC terminadas en Z. Requiere X-User-Id y X-User-Role: CLIENTE, ORGANIZADOR o ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resultados paginados", content = @Content(mediaType = ERROR,
                    schema = @Schema(implementation = EventoBusquedaResponse.class), examples = @ExampleObject(value = """
                            {"contenido":[{"id":"672a1f77bcf86cd799439011","nombre":"Festival Central","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","ciudad":"Buenos Aires","estado":"publicado","ventaSuspendida":false,"precioMinimo":15000.00}],"pagina":0,"tamano":20,"totalElementos":1,"totalPaginas":1}
                            """))),
            @ApiResponse(responseCode = "400", description = "Parámetros inválidos", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol no admitido", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class)))
    })
    public EventoBusquedaResponse buscar(
            @Parameter(name = "tipo", description = "Tipo exacto; se combina con los demás filtros con AND.", example = "MUSICA", schema = @Schema(type = "string"))
            @RequestParam(required = false) String tipo,
            @Parameter(name = "organizadorId", description = "ID del organizador; igualdad exacta.", example = "organizador-42", schema = @Schema(type = "string"))
            @RequestParam(required = false) String organizadorId,
            @Parameter(name = "participante", description = "Nombre exacto de un participante.", example = "Artista A", schema = @Schema(type = "string"))
            @RequestParam(required = false) String participante,
            @Parameter(name = "ciudad", description = "Ciudad copiada del lugar; igualdad exacta.", example = "Buenos Aires", schema = @Schema(type = "string"))
            @RequestParam(required = false) String ciudad,
            @Parameter(name = "fechaDesde", description = "Límite inferior UTC inclusivo, ISO-8601 terminado en Z.", example = "2027-05-01T00:00:00Z", schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String fechaDesde,
            @Parameter(name = "fechaHasta", description = "Límite superior UTC inclusivo, ISO-8601 terminado en Z.", example = "2027-05-31T23:59:59Z", schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String fechaHasta,
            @Parameter(name = "orden", description = "Criterio de orden. Default: fecha.", example = "fecha", schema = @Schema(type = "string", allowableValues = {"fecha", "precio", "relevancia"}, defaultValue = "fecha"))
            @RequestParam(required = false) String orden,
            @Parameter(name = "direccion", description = "Dirección del orden por precio; no altera otros órdenes. Default: asc.", example = "asc", schema = @Schema(type = "string", allowableValues = {"asc", "desc"}, defaultValue = "asc"))
            @RequestParam(required = false) String direccion,
            @Parameter(name = "q", description = "Texto libre en nombre, descripción y participantes; puntúa con orden=relevancia.", example = "Festival", schema = @Schema(type = "string"))
            @RequestParam(required = false) String q,
            @Parameter(name = "page", description = "Índice de página desde cero; mínimo 0, default 0.", example = "0", schema = @Schema(type = "integer", defaultValue = "0", minimum = "0"))
            @RequestParam(required = false) String page,
            @Parameter(name = "size", description = "Cantidad por página; rango 1–100, default 20.", example = "20", schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100"))
            @RequestParam(required = false) String size,
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true, example = "cliente-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(allowableValues = {"CLIENTE", "ORGANIZADOR", "ADMIN"}), example = "CLIENTE")
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        return consultaService.buscar(tipo, organizadorId, participante, ciudad, fechaDesde, fechaHasta,
                orden, direccion, q, page, size, userId, userRole);
    }

    @GetMapping("/{eventoId}")
    @Operation(summary = "Consultar detalle público de un evento", description = "Devuelve los datos del evento, el lugar local y sus sectores. Para eventos publicados o agotados consulta GET /disponibilidad/eventos/{eventoId} en Reservas y combina la respuesta por sectorId; si Reservas falla, la disponibilidad queda en null. Para borradores, cancelados y finalizados no consulta Reservas. Los borradores solo son visibles a su organizador dueño o ADMIN. Requiere X-User-Id y X-User-Role: CLIENTE, ORGANIZADOR o ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalle del evento", content = @Content(mediaType = ERROR,
                    schema = @Schema(implementation = EventoDetalleResponse.class), examples = @ExampleObject(value = """
                            {"id":"672a1f77bcf86cd799439011","nombre":"Festival Central","descripcion":"Música en vivo","tipo":"MUSICA","organizadorId":"organizador-42","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"publicado","ventaSuspendida":false,"lugar":{"id":"507f1f77bcf86cd799439011","nombre":"Estadio Central","direccion":"Av. Central 100","ciudad":"Buenos Aires"},"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":1,"asientosPorFila":2,"capacidad":null,"precio":15000.00,"disponibilidad":{"capacidadTotal":2,"cantidadDisponible":1,"asientosDisponibles":[{"fila":1,"numero":2}]}}]}
                            """))),
            @ApiResponse(responseCode = "401", description = "Falta identidad", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol no admitido", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente o borrador ajeno", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno", content = @Content(mediaType = ERROR, schema = @Schema(implementation = ApiError.class)))
    })
    public EventoDetalleResponse detalle(@PathVariable String eventoId,
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true, example = "cliente-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(allowableValues = {"CLIENTE", "ORGANIZADOR", "ADMIN"}), example = "CLIENTE")
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        return consultaService.detalle(eventoId, userId, userRole);
    }
}
