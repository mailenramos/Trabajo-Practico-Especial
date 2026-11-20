package com.ticketya.gestionlugares.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.domain.EstadoPersonalAcceso;
import com.ticketya.gestionlugares.repository.PersonalAccesoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/internal/eventos/{eventoId}/personal-acceso")
@Tag(name = "Integración interna")
public class PersonalAccesoInternoController {

    private final PersonalAccesoRepository repository;

    public PersonalAccesoInternoController(PersonalAccesoRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{usuarioId}/autorizacion")
    @Operation(summary = "Consultar autorización actual de personal", description = "Sin caché ni headers de usuario; ruta interna fuera del Gateway. Evento o asignación inexistente devuelve autorizado=false.")
    @ApiResponse(responseCode = "200", description = "Autorización actual")
    public AutorizacionPersonalAccesoResponse consultar(@PathVariable String eventoId, @PathVariable String usuarioId) {
        boolean autorizado = repository.existsByEventoIdAndUsuarioIdAndEstado(
                eventoId, usuarioId, EstadoPersonalAcceso.ACTIVA);
        return new AutorizacionPersonalAccesoResponse(eventoId, usuarioId, autorizado);
    }
}
