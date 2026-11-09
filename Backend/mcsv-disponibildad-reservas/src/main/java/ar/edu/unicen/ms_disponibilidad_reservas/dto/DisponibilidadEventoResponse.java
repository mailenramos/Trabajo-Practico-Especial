package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.util.List;

public record DisponibilidadEventoResponse(String eventoId, List<SectorDisponibilidadResponse> sectores) {
}
