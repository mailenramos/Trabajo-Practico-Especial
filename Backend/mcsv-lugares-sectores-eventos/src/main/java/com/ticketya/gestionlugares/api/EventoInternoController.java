package com.ticketya.gestionlugares.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.service.EventoService;
import com.ticketya.gestionlugares.domain.Evento;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/internal/eventos")
@Tag(name = "Integración interna", description = "Contratos directos entre microservicios. Estas rutas no se publican por el API Gateway.")
public class EventoInternoController {

    private final EventoService eventoService;
    private final ObjectMapper objectMapper;

    public EventoInternoController(EventoService eventoService, ObjectMapper objectMapper) {
        this.eventoService = eventoService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{eventoId}")
    @Operation(summary = "Consultar evento interno", description = "Consulta directa para Compras y otros servicios: lee el evento actual sin caché en cualquier estado, no requiere X-User-Id ni X-User-Roles y no se expone por el API Gateway. Incluye organizadorId, requiereFila, fechaHora y los datos de sectores; fechaInicio conserva el mismo instante para Compras. Los precios aún no definidos se devuelven como null.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evento encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EventoInternoResponse.class),
                            examples = @ExampleObject(name = "Evento para Compras", value = ApiExamples.EVENTO_INTERNO_COMPRAS))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public EventoInternoResponse consultar(@PathVariable String eventoId) {
        return EventoInternoResponse.from(eventoService.buscarEvento(eventoId));
    }

    @PutMapping("/{eventoId}/politica-fila")
    @Operation(summary = "Actualizar política de fila", description = "Endpoint interno para MS7; no requiere X-User-Id ni X-User-Roles y no se expone por el API Gateway. Requiere un booleano JSON. Repetir el mismo valor no escribe ni incrementa la versión. En un evento cancelado solo activar responde 409; desactivar está permitido. Se evalúa primero la existencia (404), después el body (400) y luego el estado. Un conflicto de versión también responde 409.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PoliticaFilaRequest.class),
                            examples = {
                                    @ExampleObject(name = "Activar", value = "{\"requiereFila\":true}"),
                                    @ExampleObject(name = "Desactivar", value = "{\"requiereFila\":false}")
                            })))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Política actual",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PoliticaFilaResponse.class),
                            examples = @ExampleObject(value = "{\"eventoId\":\"672a1f77bcf86cd799439011\",\"requiereFila\":true}"))),
            @ApiResponse(responseCode = "400", description = "Body ausente, mal formado o sin booleano válido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Evento inexistente",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Activación en evento cancelado o conflicto de versión",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public PoliticaFilaResponse actualizarPoliticaFila(@PathVariable String eventoId,
            @RequestBody(required = false) String body) {
        Evento evento = eventoService.buscarEvento(eventoId);
        boolean requiereFila = leerPoliticaFila(body);
        Evento actualizado = eventoService.actualizarPoliticaFila(evento, requiereFila);
        return new PoliticaFilaResponse(actualizado.getId(), actualizado.isRequiereFila());
    }

    private boolean leerPoliticaFila(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Se requiere un cuerpo JSON con requiereFila booleano");
        }
        JsonNode json;
        try {
            json = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("El cuerpo JSON está mal formado", exception);
        }
        JsonNode valor = json != null && json.isObject() ? json.get("requiereFila") : null;
        if (valor == null || !valor.isBoolean()) {
            throw new IllegalArgumentException("requiereFila debe ser un booleano");
        }
        return valor.booleanValue();
    }

    public record PoliticaFilaRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean requiereFila) { }

    public record PoliticaFilaResponse(String eventoId, boolean requiereFila) { }
}
