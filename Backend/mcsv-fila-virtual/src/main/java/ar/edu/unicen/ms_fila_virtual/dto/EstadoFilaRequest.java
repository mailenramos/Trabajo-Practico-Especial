package ar.edu.unicen.ms_fila_virtual.dto;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import jakarta.validation.constraints.NotNull;

public record EstadoFilaRequest(@NotNull(message = "es obligatorio") EstadoFila estado) {
}
