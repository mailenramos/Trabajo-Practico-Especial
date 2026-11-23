package com.ticketya.preferencias.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.preferencias.service.AvisoEventosService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/preferencias")
@Tag(name = "Avisos internos")
public class AvisoEventosPublicadosController {
    public static final String AVISO_ID_HEADER = "X-Aviso-Id";

    private final AvisoEventosService avisoEventosService;

    public AvisoEventosPublicadosController(AvisoEventosService avisoEventosService) {
        this.avisoEventosService = avisoEventosService;
    }

    @PostMapping("/eventos-publicados")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Recibir un evento publicado",
            description = "Uso interno entre servicios: requiere X-Service-Token y no se expone por el Gateway.",
            parameters = @Parameter(name = "X-Service-Token", in = ParameterIn.HEADER, required = true,
                    description = "Token de servicio compartido"))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Avisos aceptados o sin destinatarios"),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Token de servicio inválido o ausente",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Falló al menos un envío a Notificaciones",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public AvisoEventoResponse recibirPublicacion(
            @Valid @RequestBody EventoPublicadoAvisoRequest evento,
            @Parameter(description = "Identificador de correlación opcional")
            @RequestHeader(name = AVISO_ID_HEADER, required = false) String avisoId) {
        return avisoEventosService.procesar(evento, avisoId);
    }
}
