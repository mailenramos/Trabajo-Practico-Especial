package com.ticketya.msvc.entradas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record EmisionRequestDTO(
        @NotNull UUID idCompra,
        @NotBlank String idCliente,
        @NotNull @Valid EventoResumenDTO evento,
        @NotEmpty List<@NotNull @Valid LugarAEmitirDTO> lugares) {
}
