package com.ticketya.preferencias.api;

import java.time.Instant;
import java.util.List;

public record RecomendacionResponse(
        String eventoId,
        String nombre,
        String tipo,
        List<String> participantes,
        String ciudad,
        Instant fechaHora,
        String estado,
        int puntuacion) {
}
