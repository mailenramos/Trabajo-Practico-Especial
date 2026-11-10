package com.ticketya.gestionlugares.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sector embebido en un lugar. En sectores numerados se configuran filas y asientosPorFila; en los no numerados, capacidad.")
public record Sector(
        String id,
        String nombre,
        TipoSector tipo,
        Integer filas,
        Integer asientosPorFila,
        Integer capacidad) {
}
