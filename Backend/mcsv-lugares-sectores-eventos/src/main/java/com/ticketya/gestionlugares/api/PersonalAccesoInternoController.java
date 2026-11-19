package com.ticketya.gestionlugares.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.domain.EstadoPersonalAcceso;
import com.ticketya.gestionlugares.repository.PersonalAccesoRepository;

@RestController
@RequestMapping("/internal/eventos/{eventoId}/personal-acceso")
public class PersonalAccesoInternoController {

    private final PersonalAccesoRepository repository;

    public PersonalAccesoInternoController(PersonalAccesoRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{usuarioId}/autorizacion")
    public AutorizacionPersonalAccesoResponse consultar(@PathVariable String eventoId, @PathVariable String usuarioId) {
        boolean autorizado = repository.existsByEventoIdAndUsuarioIdAndEstado(
                eventoId, usuarioId, EstadoPersonalAcceso.ACTIVA);
        return new AutorizacionPersonalAccesoResponse(eventoId, usuarioId, autorizado);
    }
}
