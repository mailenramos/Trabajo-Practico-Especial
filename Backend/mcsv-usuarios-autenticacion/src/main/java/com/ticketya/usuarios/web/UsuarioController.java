package com.ticketya.usuarios.web;

import com.ticketya.usuarios.servicio.UsuarioService;
import com.ticketya.usuarios.web.dto.Dtos.EstadoPedido;
import com.ticketya.usuarios.web.dto.Dtos.PaginaUsuariosRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.PerfilPedido;
import com.ticketya.usuarios.web.dto.Dtos.RolesPedido;
import com.ticketya.usuarios.web.dto.Dtos.UsuarioRespuesta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Requieren X-User-Id y X-User-Roles, que el Gateway completa a partir del JWT. */
@RestController
@RequestMapping("/usuarios")
@Validated
@Tag(name = "Usuarios")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "200", description = "OK")
@ApiResponse(responseCode = "401", description = "JWT o identidad ausente o inválida.", content = @Content)
@ApiResponse(responseCode = "403", description = "Rol no autorizado.", content = @Content)
public class UsuarioController {

    private final UsuarioService servicio;

    public UsuarioController(UsuarioService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/me")
    @Operation(summary = "Consultar mi perfil")
    public UsuarioRespuesta perfil(@Parameter(hidden = true) UsuarioActual actual) {
        return servicio.perfil(actual);
    }

    @PutMapping("/me")
    @ApiResponse(responseCode = "400", description = "Datos inválidos.", content = @Content)
    @Operation(summary = "Actualizar mi perfil",
            description = "Solo cambian nombre y apellido; roles, estado y correo enviados se ignoran.")
    public UsuarioRespuesta actualizarPerfil(@Parameter(hidden = true) UsuarioActual actual,
                                             @Valid @RequestBody PerfilPedido pedido) {
        return servicio.actualizarPerfil(actual, pedido);
    }

    @GetMapping
    @Operation(summary = "Listar usuarios (ADMIN)",
            description = "Paginado y ordenado por correo. Filtros opcionales: rol exacto y correo parcial.")
    public PaginaUsuariosRespuesta listar(@Parameter(hidden = true) UsuarioActual actual,
                                          @RequestParam(required = false) String rol,
                                          @RequestParam(required = false) String correo,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return servicio.listar(actual, rol, correo, page, size);
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado.", content = @Content)
    @Operation(summary = "Consultar un usuario (ADMIN)")
    public UsuarioRespuesta obtener(@Parameter(hidden = true) UsuarioActual actual, @PathVariable UUID id) {
        return servicio.obtener(actual, id);
    }

    @PutMapping("/{id}/roles")
    @ApiResponse(responseCode = "400", description = "Datos inválidos.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado.", content = @Content)
    @ApiResponse(responseCode = "409", description = "Cambio incompatible con las reglas de administración.", content = @Content)
    @Operation(summary = "Asignar roles (ADMIN)",
            description = "Reemplaza los roles del usuario. Rigen desde su próximo inicio de sesión.")
    public UsuarioRespuesta actualizarRoles(@Parameter(hidden = true) UsuarioActual actual,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody RolesPedido pedido) {
        return servicio.actualizarRoles(actual, id, pedido.roles());
    }

    @PutMapping("/{id}/estado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado.", content = @Content)
    @ApiResponse(responseCode = "409", description = "Cambio incompatible con las reglas de administración.", content = @Content)
    @Operation(summary = "Activar o desactivar un usuario (ADMIN)", description = "Borrado lógico.")
    public UsuarioRespuesta cambiarEstado(@Parameter(hidden = true) UsuarioActual actual,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody EstadoPedido pedido) {
        return servicio.cambiarEstado(actual, id, pedido.activo());
    }
}
