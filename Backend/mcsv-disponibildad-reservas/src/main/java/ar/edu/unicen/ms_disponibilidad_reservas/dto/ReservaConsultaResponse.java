package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.time.Instant;
import java.util.List;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;

public record ReservaConsultaResponse(
        String reservaId,
        String clienteId,
        String eventoId,
        EstadoReserva estado,
        Instant expiraEn,
        List<DetalleReservaConsultaResponse> detalles) {
}
