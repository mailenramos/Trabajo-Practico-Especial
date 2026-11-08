package com.ticketya.gestionlugares.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Tipo de sector: NUMERADO o NO_NUMERADO.")
public enum TipoSector {
    NUMERADO,
    NO_NUMERADO
}
