package com.ticketya.gestionlugares.domain;

public record AsientoNumerado(String sectorId, int fila, int numero) {

    public static AsientoNumerado de(Sector sector, int fila, int numero) {
        if (sector == null || sector.tipo() != TipoSector.NUMERADO
                || fila < 1 || fila > sector.filas()
                || numero < 1 || numero > sector.asientosPorFila()) {
            throw new IllegalArgumentException("El asiento está fuera de los límites del sector numerado");
        }
        return new AsientoNumerado(sector.id(), fila, numero);
    }
}
