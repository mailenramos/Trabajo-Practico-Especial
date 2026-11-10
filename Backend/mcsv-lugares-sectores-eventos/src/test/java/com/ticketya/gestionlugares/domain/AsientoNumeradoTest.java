package com.ticketya.gestionlugares.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AsientoNumeradoTest {

    private final Sector sector = new Sector(
            "550e8400-e29b-41d4-a716-446655440000", "Platea", TipoSector.NUMERADO, 3, 4, null);

    @Test
    void derivaLaIdentidadDelAsientoDentroDeLosLimites() {
        AsientoNumerado asiento = AsientoNumerado.de(sector, 3, 4);

        assertEquals(sector.id(), asiento.sectorId());
        assertEquals(3, asiento.fila());
        assertEquals(4, asiento.numero());
    }

    @Test
    void rechazaFilaONumeroFueraDeRango() {
        assertThrows(IllegalArgumentException.class, () -> AsientoNumerado.de(sector, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> AsientoNumerado.de(sector, 1, 5));
    }
}
