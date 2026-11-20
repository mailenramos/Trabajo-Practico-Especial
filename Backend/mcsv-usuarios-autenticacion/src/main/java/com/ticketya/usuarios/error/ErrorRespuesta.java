package com.ticketya.usuarios.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/** Formato de error acordado por el grupo: timestamp, status, error, mensaje y campos. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorRespuesta(
        Instant timestamp,
        int status,
        String error,
        String mensaje,
        Map<String, String> campos) {
}
