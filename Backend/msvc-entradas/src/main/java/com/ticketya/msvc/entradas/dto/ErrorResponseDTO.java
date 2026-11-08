package com.ticketya.msvc.entradas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Formato de error acordado por el grupo: timestamp, status, error, mensaje y, solo en errores
 * de validación, campos. "codigo" es un agregado propio para distinguir los distintos 409.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponseDTO(
        LocalDateTime timestamp,
        int status,
        String error,
        String codigo,
        String mensaje,
        Map<String, String> campos) {
}
