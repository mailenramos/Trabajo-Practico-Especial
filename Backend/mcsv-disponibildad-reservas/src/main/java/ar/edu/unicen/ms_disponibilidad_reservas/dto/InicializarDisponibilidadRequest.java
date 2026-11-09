package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Datos de disponibilidad enviados por MS1 para un evento. */
public record InicializarDisponibilidadRequest(
        @NotNull(message = "es obligatorio")
        @Min(value = 4, message = "debe ser exactamente 4")
        @Max(value = 4, message = "debe ser exactamente 4")
        Integer limiteEntradasPorCliente,
        @NotEmpty List<@Valid SectorInicializacionRequest> sectores) {
}
