package com.ticketya.msvc.entradas.dto;

import com.ticketya.msvc.entradas.entities.EstadoEntrada;

import java.util.UUID;

public record EntradaEmitidaDTO(
        UUID idEntrada,
        String idSector,
        String fila,
        Integer numero,
        UUID codigoQr,
        EstadoEntrada estado) {
}
