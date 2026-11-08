package com.ticketya.gestionlugares.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;

public interface EventoRepository extends MongoRepository<Evento, String> {
    List<Evento> findByOrganizadorIdAndEstado(String organizadorId, EstadoEvento estado);
    List<Evento> findByEstadoAndFechaHoraBefore(EstadoEvento estado, Instant fechaHora);
    List<Evento> findByCiudadAndEstadoAndFechaHoraAfter(String ciudad, EstadoEvento estado, Instant fechaHora);
}
