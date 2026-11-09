package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetalleReservaConsultaResponse(
        String sectorId,
        Integer fila,
        Integer numero,
        Integer cantidad) {
}
