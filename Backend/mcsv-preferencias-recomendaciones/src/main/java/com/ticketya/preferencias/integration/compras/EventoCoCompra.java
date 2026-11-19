package com.ticketya.preferencias.integration.compras;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoCoCompra(String eventoId, int cantidad) {
}
