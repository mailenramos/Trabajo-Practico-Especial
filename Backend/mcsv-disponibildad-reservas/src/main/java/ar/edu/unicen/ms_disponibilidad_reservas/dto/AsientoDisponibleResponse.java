package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;

/** Public seat state; no reservation or customer identifiers are exposed. */
public record AsientoDisponibleResponse(Integer fila, Integer numero, EstadoAsiento estado) {
}
