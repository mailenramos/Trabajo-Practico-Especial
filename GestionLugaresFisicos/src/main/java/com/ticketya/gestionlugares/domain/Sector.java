package com.ticketya.gestionlugares.domain;

public record Sector(
        String id,
        String nombre,
        TipoSector tipo,
        Integer filas,
        Integer asientosPorFila,
        Integer capacidad) {
}
