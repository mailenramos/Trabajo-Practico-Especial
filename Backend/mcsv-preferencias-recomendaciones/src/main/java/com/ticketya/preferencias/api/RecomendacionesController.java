package com.ticketya.preferencias.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.preferencias.security.UserIdentity;
import com.ticketya.preferencias.service.RecomendacionesService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/recomendaciones")
@Validated
@Tag(name = "Recomendaciones")
@SecurityRequirement(name = "bearerAuth")
public class RecomendacionesController {
    private final RecomendacionesService recomendacionesService;

    public RecomendacionesController(RecomendacionesService recomendacionesService) {
        this.recomendacionesService = recomendacionesService;
    }

    @GetMapping("/me")
    @Operation(summary = "Consultar recomendaciones para el cliente autenticado")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recomendaciones consultadas"),
            @ApiResponse(responseCode = "401", description = "Falta identidad",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene el rol CLIENTE",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "El catálogo de eventos no está disponible",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public List<RecomendacionResponse> obtener(
            @RequestAttribute(UserIdentity.USER_ID_ATTRIBUTE) String usuarioId) {
        return recomendacionesService.recomendar(usuarioId);
    }
}
