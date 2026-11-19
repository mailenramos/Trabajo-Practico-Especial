package com.ticketya.preferencias.integration.compras;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoComprasResponse(String eventoId, List<EventoCoCompra> coCompras) {
}
