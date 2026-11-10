package com.ticketya.gestionlugares.reservas.dto;

import java.util.List;

public record DisponibilidadEventoResponse(String eventoId, List<SectorDisponibilidadResponse> sectores) { }
