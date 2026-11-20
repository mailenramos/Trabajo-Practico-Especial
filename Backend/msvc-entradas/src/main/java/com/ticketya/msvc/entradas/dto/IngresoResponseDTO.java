package com.ticketya.msvc.entradas.dto;

import java.time.Instant;
import java.util.UUID;

/** Ingreso registrado: incluye la ubicación para que el personal oriente al asistente. */
public record IngresoResponseDTO(
        UUID idIngreso,
        UUID idEntrada,
        String idEvento,
        SectorResumenDTO sector,
        Integer fila,
        Integer numero,
        String validadoPor,
        boolean excepcional,
        Instant validadoEl) {
}
