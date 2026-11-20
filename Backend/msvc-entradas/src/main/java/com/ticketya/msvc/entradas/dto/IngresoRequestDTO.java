package com.ticketya.msvc.entradas.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Pedido de validación: el código QR leído en el acceso. */
public record IngresoRequestDTO(@NotNull(message = "es obligatorio") UUID codigoQr) {
}
