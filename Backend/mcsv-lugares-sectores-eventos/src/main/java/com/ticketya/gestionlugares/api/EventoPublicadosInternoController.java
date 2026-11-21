package com.ticketya.gestionlugares.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.service.EventoConsultaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/internal/eventos-publicados")
@Tag(name = "Integración interna", description = "Contratos directos entre microservicios no publicados por el API Gateway.")
public class EventoPublicadosInternoController {

    private final EventoConsultaService consultaService;

    public EventoPublicadosInternoController(EventoConsultaService consultaService) {
        this.consultaService = consultaService;
    }

    @GetMapping
    @Operation(summary = "Consultar eventos publicados y agotados para recomendaciones",
            description = "Ruta interna para MS8, sin headers de usuario; no se expone por el API Gateway. Devuelve eventos publicados o agotados, ordenados por fechaHora e ID ascendente. page es cero-based (default 0); size default 100 y máximo 200.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resultados paginados",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = EventoPublicadosResponse.class))),
            @ApiResponse(responseCode = "400", description = "page o size inválidos",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Error interno",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public EventoPublicadosResponse consultar(
            @Parameter(name = "page", description = "Índice de página desde cero; default 0.",
                    schema = @Schema(type = "integer", defaultValue = "0", minimum = "0"))
            @RequestParam(required = false) String page,
            @Parameter(name = "size", description = "Cantidad por página; rango 1–200, default 100.",
                    schema = @Schema(type = "integer", defaultValue = "100", minimum = "1", maximum = "200"))
            @RequestParam(required = false) String size) {
        return consultaService.eventosPublicados(page, size);
    }
}
