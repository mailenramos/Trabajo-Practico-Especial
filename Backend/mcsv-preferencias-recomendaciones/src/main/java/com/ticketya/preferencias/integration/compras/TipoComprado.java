package com.ticketya.preferencias.integration.compras;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TipoComprado(String tipo, int cantidad) {
}
