package com.ticketya.gestionlugares.service;

import java.time.Instant;

public record BusquedaCatalogo(String tipo, String organizadorId, String participante, String ciudad,
        Instant desde, Instant hasta, String orden, String direccion, String q, int page, int size) {
}
