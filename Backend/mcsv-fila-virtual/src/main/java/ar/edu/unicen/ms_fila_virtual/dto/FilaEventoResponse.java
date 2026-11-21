package ar.edu.unicen.ms_fila_virtual.dto;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;

public record FilaEventoResponse(String eventoId, EstadoFila estado, boolean cancelado, int cupo,
        int duracionMinutos, long cantidadEsperando, long cantidadHabilitados) {
}
