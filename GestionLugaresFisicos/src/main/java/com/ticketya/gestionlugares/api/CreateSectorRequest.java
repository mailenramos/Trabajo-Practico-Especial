package com.ticketya.gestionlugares.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.ticketya.gestionlugares.domain.TipoSector;

public record CreateSectorRequest(
        @NotBlank String nombre,
        @NotNull TipoSector tipo,
        Integer filas,
        Integer asientosPorFila,
        Integer capacidad) {
}
