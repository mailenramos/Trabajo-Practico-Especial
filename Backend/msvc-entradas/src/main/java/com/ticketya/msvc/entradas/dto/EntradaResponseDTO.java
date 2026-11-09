package com.ticketya.msvc.entradas.dto;

import com.ticketya.msvc.entradas.entities.EstadoEntrada;

import java.util.UUID;

public record EntradaResponseDTO(
        UUID idEntrada,
        UUID codigoQr,
        EstadoEntrada estado,
        EventoResumenDTO evento,
        SectorResumenDTO sector,
        Integer fila,
        Integer numero,
        UUID idCompra,
        String idCliente) {
}
