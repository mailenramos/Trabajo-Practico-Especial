package com.ticketya.gestionlugares.api;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;
import com.ticketya.gestionlugares.domain.EstadoPersonalAcceso;
import com.ticketya.gestionlugares.service.PersonalAccesoService;

@RestController
@RequestMapping("/eventos/{eventoId}/personal-acceso")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Personal de acceso")
public class PersonalAccesoController {
    private final PersonalAccesoService service;

    public PersonalAccesoController(PersonalAccesoService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Asignar personal", description = "El organizador titular o ADMIN asigna un usuario activo con rol PERSONAL_ACCESO, consultado en MS6.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Asignación creada o reactivada"),
            @ApiResponse(responseCode = "400", description = "Correo inválido, usuario inactivo o rol insuficiente"),
            @ApiResponse(responseCode = "403", description = "Sin autorización"),
            @ApiResponse(responseCode = "404", description = "Evento o usuario inexistente"),
            @ApiResponse(responseCode = "409", description = "Asignación activa duplicada"),
            @ApiResponse(responseCode = "503", description = "Servicio de usuarios no disponible")
    })
    public ResponseEntity<AsignacionResponse> asignar(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody AsignarRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AsignacionResponse.from(service.asignar(eventoId, request.correo(), userId, roles)));
    }

    @GetMapping
    @Operation(summary = "Listar personal", description = "Solo organizador titular o ADMIN; incluye asignaciones revocadas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asignaciones"),
            @ApiResponse(responseCode = "403", description = "Sin autorización"),
            @ApiResponse(responseCode = "404", description = "Evento inexistente")
    })
    public List<AsignacionResponse> listar(@PathVariable String eventoId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return service.listar(eventoId, userId, roles).stream().map(AsignacionResponse::from).toList();
    }

    @DeleteMapping("/{usuarioId}")
    @Operation(summary = "Revocar personal", description = "Solo organizador titular o ADMIN; revocar una asignación revocada es idempotente.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Asignación revocada"),
            @ApiResponse(responseCode = "403", description = "Sin autorización"),
            @ApiResponse(responseCode = "404", description = "Evento o asignación inexistente")
    })
    public ResponseEntity<Void> revocar(@PathVariable String eventoId, @PathVariable String usuarioId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        service.revocar(eventoId, usuarioId, userId, roles);
        return ResponseEntity.noContent().build();
    }

    public record AsignarRequest(@NotBlank @Email String correo) { }

    public record AsignacionResponse(String eventoId, String usuarioId, EstadoPersonalAcceso estado,
            Instant asignadaEn, Instant revocadaEn) {
        static AsignacionResponse from(AsignacionPersonalAcceso asignacion) {
            return new AsignacionResponse(asignacion.getEventoId(), asignacion.getUsuarioId(),
                    asignacion.getEstado(), asignacion.getAsignadaEn(), asignacion.getRevocadaEn());
        }
    }
}
