package com.ticketya.usuarios.web;

import com.ticketya.usuarios.servicio.UsuarioService;
import com.ticketya.usuarios.web.dto.Dtos.ContactoRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.IdentidadRespuesta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Rutas internas entre microservicios: el API Gateway no las enruta (responde 404). */
@RestController
@RequestMapping("/internal/usuarios")
@Tag(name = "Interno")
public class UsuarioInternoController {

    private final UsuarioService servicio;

    public UsuarioInternoController(UsuarioService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/{id}/contacto")
    @Operation(summary = "Correo de un usuario (Notificaciones)",
            description = "Responde también para cuentas inactivas.")
    public ContactoRespuesta contacto(@PathVariable UUID id) {
        return servicio.contacto(id);
    }

    @GetMapping("/por-correo")
    @Operation(summary = "Identificador y roles por correo (Lugares, sectores y eventos)")
    public IdentidadRespuesta porCorreo(@RequestParam String correo) {
        return servicio.porCorreo(correo);
    }
}
