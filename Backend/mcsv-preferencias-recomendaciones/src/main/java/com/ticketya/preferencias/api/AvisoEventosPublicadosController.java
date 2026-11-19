package com.ticketya.preferencias.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.preferencias.service.AvisoEventosService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/preferencias")
public class AvisoEventosPublicadosController {
    public static final String AVISO_ID_HEADER = "X-Aviso-Id";

    private final AvisoEventosService avisoEventosService;

    public AvisoEventosPublicadosController(AvisoEventosService avisoEventosService) {
        this.avisoEventosService = avisoEventosService;
    }

    @PostMapping("/eventos-publicados")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AvisoEventoResponse recibirPublicacion(
            @Valid @RequestBody EventoPublicadoAvisoRequest evento,
            @RequestHeader(name = AVISO_ID_HEADER, required = false) String avisoId) {
        return avisoEventosService.procesar(evento, avisoId);
    }
}
