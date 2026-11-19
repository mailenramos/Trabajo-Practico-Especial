package com.ticketya.gestionlugares.api;

public record AutorizacionPersonalAccesoResponse(String eventoId, String usuarioId, boolean autorizado) {
}
