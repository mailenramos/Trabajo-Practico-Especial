package com.ticketya.gestionlugares.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record ActualizarPrecioRequest(@NotNull @DecimalMin("0.00") BigDecimal precio) {
}
