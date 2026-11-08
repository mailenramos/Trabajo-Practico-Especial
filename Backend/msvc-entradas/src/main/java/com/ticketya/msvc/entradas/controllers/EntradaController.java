package com.ticketya.msvc.entradas.controllers;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EmisionResponseDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.services.EntradaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
public class EntradaController {

    private final EntradaService entradaService;

    // ---------- Públicos (cliente, vía API Gateway) ----------

    @GetMapping("/entradas")
    public ResponseEntity<PaginaEntradasResponseDTO> getEntradas(
            @RequestHeader("X-User-Id") String idUsuario,
            @RequestParam(required = false) EstadoEntrada estado,
            @RequestParam(required = false) String idEvento,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(
                entradaService.getEntradas(idUsuario, estado, idEvento, page, size));
    }

    @GetMapping("/entradas/{id}")
    public ResponseEntity<EntradaResponseDTO> getEntrada(
            @RequestHeader("X-User-Id") String idUsuario,
            @PathVariable UUID id) {

        return ResponseEntity.ok(entradaService.getEntrada(idUsuario, id));
    }

    // ---------- Internos (solo servicio de compras y devoluciones) ----------

    @PostMapping("/internal/entradas/emision")
    public ResponseEntity<EmisionResponseDTO> emitirEntradas(
            @Valid @RequestBody EmisionRequestDTO request) {

        ResultadoEmisionDTO resultado = entradaService.emitirEntradas(request);
        HttpStatus estado = resultado.creada() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(estado).body(resultado.emision());
    }

    @GetMapping("/internal/entradas/{id}")
    public ResponseEntity<EntradaResponseDTO> consultarEntradaInterna(
            @PathVariable UUID id) {

        return ResponseEntity.ok(entradaService.consultarEntradaInterna(id));
    }

    @PostMapping("/internal/entradas/{id}/reintegro")
    public ResponseEntity<EntradaResponseDTO> marcarEnReintegro(
            @PathVariable UUID id) {

        return ResponseEntity.ok(entradaService.marcarEnReintegro(id));
    }

    @PostMapping("/internal/entradas/{id}/devolucion")
    public ResponseEntity<EntradaResponseDTO> marcarDevuelta(
            @PathVariable UUID id) {

        return ResponseEntity.ok(entradaService.marcarDevuelta(id));
    }

    @PostMapping("/internal/eventos/{idEvento}/entradas/reintegro")
    public ResponseEntity<ReintegroEventoResponseDTO> marcarEntradasEnReintegro(
            @PathVariable String idEvento) {

        return ResponseEntity.ok(entradaService.reintegroPorCancelacionEvento(idEvento));
    }
}
