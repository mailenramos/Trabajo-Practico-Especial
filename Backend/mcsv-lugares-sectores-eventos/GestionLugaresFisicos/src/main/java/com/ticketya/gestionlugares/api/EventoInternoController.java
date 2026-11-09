package com.ticketya.gestionlugares.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.service.EventoService;

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

    public EventoInternoController(EventoService eventoService) {
        this.eventoService = eventoService;
    }

    @GetMapping("/{eventoId}")
    @Operation(summary = "Consultar evento para Compras", description = "Endpoint interno invocado directamente por Compras. Devuelve cualquier estado, no requiere X-User-Id ni X-User-Role y no se expone por el API Gateway. Los precios aún no definidos se devuelven como null.")
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
}
