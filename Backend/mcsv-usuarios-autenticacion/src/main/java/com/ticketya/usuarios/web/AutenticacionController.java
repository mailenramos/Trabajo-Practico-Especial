package com.ticketya.usuarios.web;

import com.ticketya.usuarios.servicio.AutenticacionService;
import com.ticketya.usuarios.web.dto.Dtos.LoginPedido;
import com.ticketya.usuarios.web.dto.Dtos.RegistroPedido;
import com.ticketya.usuarios.web.dto.Dtos.TokenRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.UsuarioRespuesta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rutas públicas: el Gateway las atiende sin token. */
@RestController
@RequestMapping("/auth")
@Tag(name = "Autenticación")
public class AutenticacionController {

    private final AutenticacionService servicio;

    public AutenticacionController(AutenticacionService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/registro")
    @Operation(summary = "Registrarse como cliente",
            description = "Crea un usuario activo con rol CLIENTE. Cualquier rol enviado en el cuerpo se ignora.")
    public ResponseEntity<UsuarioRespuesta> registrar(@Valid @RequestBody RegistroPedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.registrar(pedido));
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión",
            description = "Devuelve un JWT HS256 con sub, email, roles, iss, iat y exp (por defecto, una hora).")
    public TokenRespuesta login(@Valid @RequestBody LoginPedido pedido) {
        return servicio.login(pedido);
    }
}
