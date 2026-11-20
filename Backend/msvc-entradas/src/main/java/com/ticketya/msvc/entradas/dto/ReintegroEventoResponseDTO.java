package com.ticketya.msvc.entradas.dto;

/**
 * Respuesta del cierre de un evento cancelado. {@code cerrado} es siempre true cuando la
 * operación termina bien: Compras no da por terminada la cancelación hasta recibirlo.
 */
public record ReintegroEventoResponseDTO(String idEvento, int entradasMarcadas, boolean cerrado) {
}
