package com.ticketya.gestionlugares.api;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.service.ForbiddenException;
import com.ticketya.gestionlugares.service.LugarService;
import com.ticketya.gestionlugares.service.UnauthorizedException;

@RestController
@RequestMapping("/lugares")
public class LugarController {

    private final LugarService lugarService;

    public LugarController(LugarService lugarService) {
        this.lugarService = lugarService;
    }

    @PostMapping
    public ResponseEntity<Lugar> crearLugar(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @Valid @RequestBody CreateLugarRequest request) {
        validarAdministrador(userId, userRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(lugarService.crearLugar(request));
    }

    @GetMapping
    public List<Lugar> listarLugares() {
        return lugarService.listarLugares();
    }

    @GetMapping("/{id}")
    public Lugar obtenerLugar(@PathVariable String id) {
        return lugarService.buscarLugar(id);
    }

    @GetMapping("/{id}/sectores")
    public List<Sector> listarSectores(@PathVariable String id) {
        return lugarService.listarSectores(id);
    }

    @PostMapping("/{id}/sectores")
    public ResponseEntity<Sector> agregarSector(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @Valid @RequestBody CreateSectorRequest request) {
        validarAdministrador(userId, userRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(lugarService.agregarSector(id, request));
    }

    private void validarAdministrador(String userId, String userRole) {
        if (userId == null || userId.isBlank() || userRole == null || userRole.isBlank()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Role");
        }
        if (!"administrador".equalsIgnoreCase(userRole.trim())) {
            throw new ForbiddenException("Solo un administrador puede gestionar lugares y sectores");
        }
    }
}
