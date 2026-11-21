package com.ticketya.msvc.entradas.controllers;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EmisionResponseDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.EntradasCompraResponseDTO;
import com.ticketya.msvc.entradas.dto.IngresoRequestDTO;
import com.ticketya.msvc.entradas.dto.IngresoResponseDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;
import com.ticketya.msvc.entradas.services.EntradaService;
import com.ticketya.msvc.entradas.services.IngresoService;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
public class EntradaController {

    private final EntradaService entradaService;
    private final IngresoService ingresoService;

    // ---------- Públicos (vía API Gateway; identidad en X-User-Id y X-User-Roles) ----------

    @GetMapping("/entradas")
    public ResponseEntity<PaginaEntradasResponseDTO> getEntradas(
            @Parameter(hidden = true) UsuarioActual usuario,
            @RequestParam(required = false) EstadoEntrada estado,
            @RequestParam(required = false) String idEvento,
            @RequestParam(required = false) String idCliente,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(
                entradaService.getEntradas(usuario, estado, idEvento, idCliente, page, size));
    }

    @GetMapping("/entradas/{id}")
    public ResponseEntity<EntradaResponseDTO> getEntrada(
            @Parameter(hidden = true) UsuarioActual usuario,
            @PathVariable UUID id) {

        return ResponseEntity.ok(entradaService.getEntrada(usuario, id));
    }

    /** Validación del QR en el acceso (PERSONAL_ACCESO asignado al evento o ADMIN). */
    @PostMapping("/eventos/{idEvento}/ingresos")
    public ResponseEntity<IngresoResponseDTO> validarIngreso(
            @Parameter(hidden = true) UsuarioActual usuario,
            @PathVariable String idEvento,
            @Valid @RequestBody IngresoRequestDTO request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ingresoService.validar(usuario, idEvento, request.codigoQr()));
    }

    // ---------- Internos (solo Compras y devoluciones; protegidos con X-Service-Token) ----------

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

    @GetMapping("/internal/compras/{idCompra}/entradas")
    public ResponseEntity<EntradasCompraResponseDTO> consultarEntradasDeCompra(
            @PathVariable UUID idCompra) {

        return ResponseEntity.ok(entradaService.consultarEntradasDeCompra(idCompra));
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

    /** Cierre del evento cancelado: persistente, idempotente y responde cerrado=true. */
    @PostMapping("/internal/eventos/{idEvento}/entradas/reintegro")
    public ResponseEntity<ReintegroEventoResponseDTO> marcarEntradasEnReintegro(
            @PathVariable String idEvento) {

        return ResponseEntity.ok(entradaService.reintegroPorCancelacionEvento(idEvento));
    }
}
