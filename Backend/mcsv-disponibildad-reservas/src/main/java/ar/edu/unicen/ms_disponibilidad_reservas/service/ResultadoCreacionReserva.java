package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.http.HttpStatus;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.ReservaCreadaResponse;

public record ResultadoCreacionReserva(HttpStatus status, ReservaCreadaResponse reserva) {
}
