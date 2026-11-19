package com.ticketya.preferencias.api;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.preferencias.security.UserIdentity;
import com.ticketya.preferencias.service.PreferenciasService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/preferencias")
@Validated
@Tag(name = "Preferencias")
public class PreferenciasController {
    private final PreferenciasService preferenciasService;

    public PreferenciasController(PreferenciasService preferenciasService) {
        this.preferenciasService = preferenciasService;
    }

    @GetMapping("/me")
    @Operation(summary = "Consultar las preferencias del cliente autenticado")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preferencias consultadas"),
            @ApiResponse(responseCode = "401", description = "Falta identidad"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene el rol CLIENTE")
    })
    public PreferenciaResponse obtener(
            @RequestAttribute(UserIdentity.USER_ID_ATTRIBUTE) String usuarioId) {
        return PreferenciaResponse.desde(preferenciasService.obtener(usuarioId));
    }

    @PutMapping("/me")
    @Operation(summary = "Reemplazar las preferencias del cliente autenticado")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preferencias guardadas"),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida"),
            @ApiResponse(responseCode = "401", description = "Falta identidad"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene el rol CLIENTE")
    })
    public PreferenciaResponse reemplazar(
            @RequestAttribute(UserIdentity.USER_ID_ATTRIBUTE) String usuarioId,
            @Valid @RequestBody ActualizarPreferenciasRequest request) {
        return PreferenciaResponse.desde(preferenciasService.reemplazar(usuarioId, request));
    }

    @DeleteMapping("/me")
    @Operation(summary = "Eliminar las preferencias del cliente autenticado")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Preferencias eliminadas"),
            @ApiResponse(responseCode = "401", description = "Falta identidad"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene el rol CLIENTE")
    })
    public ResponseEntity<Void> eliminar(
            @RequestAttribute(UserIdentity.USER_ID_ATTRIBUTE) String usuarioId) {
        preferenciasService.eliminar(usuarioId);
        return ResponseEntity.noContent().build();
    }
}
