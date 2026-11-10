package com.ticketya.msvc.entradas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record EventoResumenDTO(
        @NotBlank String id,
        @NotBlank String nombre,
        @NotNull Instant comienzaEn) {
}
