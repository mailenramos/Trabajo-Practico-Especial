package com.ticketya.preferencias.api;

import java.util.List;

public record ActualizarPreferenciasRequest(
        List<String> tiposEvento,
        List<String> artistas,
        List<String> ciudades) {
}
