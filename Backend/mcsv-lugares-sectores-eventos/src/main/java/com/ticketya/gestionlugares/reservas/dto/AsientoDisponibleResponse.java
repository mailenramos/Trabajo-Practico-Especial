package com.ticketya.gestionlugares.reservas.dto;

public record AsientoDisponibleResponse(int fila, int numero, String estado) {
    public AsientoDisponibleResponse(int fila, int numero) {
        this(fila, numero, "LIBRE");
    }
}
