package ar.edu.unicen.ms_fila_virtual.dto;

import java.time.Instant;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;

public record ConsumoTurnoResponse(Long turnoId, String eventoId, String solicitudId, EstadoTurno estado,
        Instant expiraEn, boolean autorizacionVigente) {
}
