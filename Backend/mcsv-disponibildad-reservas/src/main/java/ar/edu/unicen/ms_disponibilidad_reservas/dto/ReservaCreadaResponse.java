package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.time.Instant;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;

/** Representación HTTP mínima de una reserva recién creada o recuperada por idempotencia. */
public record ReservaCreadaResponse(String reservaId, EstadoReserva estado, Instant expiraEn) {
}
