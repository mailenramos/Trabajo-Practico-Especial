package ar.edu.unicen.ms_fila_virtual.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnoResponse(Long turnoId, String eventoId, EstadoTurno estado, Long posicion,
        Instant ingresadoEn, Instant habilitadoEn, Instant expiraEn) {
}
