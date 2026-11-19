package com.ticketya.preferencias.integration.eventos;

import java.time.Instant;
import java.util.List;

public record EventoPublicado(
        String eventoId,
        String nombre,
        String tipo,
        List<String> participantes,
        String ciudad,
        Instant fechaHora,
        String estado) {
}
