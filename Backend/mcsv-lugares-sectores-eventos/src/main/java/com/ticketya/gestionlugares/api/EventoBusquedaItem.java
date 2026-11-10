package com.ticketya.gestionlugares.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ticketya.gestionlugares.domain.Evento;

public record EventoBusquedaItem(String id, String nombre, String tipo, List<String> participantes,
        Instant fechaHora, String ciudad, String estado, boolean ventaSuspendida, BigDecimal precioMinimo) {
    public static EventoBusquedaItem from(Evento evento) {
        return new EventoBusquedaItem(evento.getId(), evento.getNombre(), evento.getTipo(),
                evento.getParticipantes(), evento.getFechaHora(), evento.getCiudad(),
                evento.getEstado().getValor(), evento.isVentaSuspendida(), evento.getPrecioMinimo());
    }
}
