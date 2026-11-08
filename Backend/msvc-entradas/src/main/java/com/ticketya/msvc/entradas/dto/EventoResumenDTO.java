package com.ticketya.msvc.entradas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record EventoResumenDTO(
        @NotBlank String id,
        @NotBlank String nombre,
        @NotNull LocalDateTime comienzaEn) {
}
