package com.ticketya.preferencias.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "preferencias")
public record Preferencia(
        @Id String usuarioId,
        List<String> tiposEvento,
        List<String> artistas,
        List<String> ciudades,
        Instant actualizadoEn) {
}
