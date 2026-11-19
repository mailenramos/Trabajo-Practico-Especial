package com.ticketya.preferencias.integration.notificaciones;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

public record NotificacionRequest(
        String acontecimientoId,
        String tipo,
        int versionEsquema,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant ocurridoEn,
        String recursoId,
        String usuarioId,
        String correlacionId,
        Datos datos) {
    public record Datos(String evento, String fecha, String lugar) {
    }
}
