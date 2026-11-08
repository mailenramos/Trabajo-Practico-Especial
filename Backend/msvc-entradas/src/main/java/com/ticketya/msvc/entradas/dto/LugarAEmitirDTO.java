package com.ticketya.msvc.entradas.dto;

import jakarta.validation.constraints.NotBlank;

/** Un lugar comprado. fila y numero son nulos en sectores no numerados (una línea por lugar). */
public record LugarAEmitirDTO(
        @NotBlank String idSector,
        @NotBlank String nombreSector,
        String fila,
        Integer numero) {
}
