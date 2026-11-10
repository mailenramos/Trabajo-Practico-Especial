package com.ticketya.gestionlugares.api;

import jakarta.validation.constraints.NotNull;

public record DisponibilidadEventoRequest(@NotNull Boolean agotado) {
}
