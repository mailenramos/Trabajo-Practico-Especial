package com.ticketya.gestionlugares.reservas.dto;

import java.util.List;

public record InicializarDisponibilidadRequest(
        int limiteEntradasPorCliente,
        List<SectorDisponibilidadRequest> sectores) {
}
