package com.ticketya.msvc.entradas.dto;

import java.util.List;
import java.util.UUID;

/** Entradas emitidas para una compra. Una lista vacía significa que no se emitió ninguna. */
public record EntradasCompraResponseDTO(UUID idCompra, List<EntradaResponseDTO> entradas) {
}
