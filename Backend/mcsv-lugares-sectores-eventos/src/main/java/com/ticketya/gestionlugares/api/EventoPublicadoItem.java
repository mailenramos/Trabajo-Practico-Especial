package com.ticketya.gestionlugares.api;

import java.time.Instant;
import java.util.List;

import com.ticketya.gestionlugares.domain.Evento;

public record EventoPublicadoItem(String eventoId, String nombre, String tipo, List<String> participantes,
        String ciudad, Instant fechaHora, String estado) {

    public static EventoPublicadoItem from(Evento evento) {
        return new EventoPublicadoItem(evento.getId(), evento.getNombre(), evento.getTipo(),
                evento.getParticipantes(), evento.getCiudad(), evento.getFechaHora(),
                evento.getEstado().getValor());
    }
}
