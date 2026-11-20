package com.ticketya.gestionlugares.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;
import com.ticketya.gestionlugares.domain.EstadoPersonalAcceso;

public interface PersonalAccesoRepository extends MongoRepository<AsignacionPersonalAcceso, String>,
        PersonalAccesoRepositoryCustom {

    Optional<AsignacionPersonalAcceso> findByEventoIdAndUsuarioId(String eventoId, String usuarioId);

    List<AsignacionPersonalAcceso> findByEventoId(String eventoId);

    boolean existsByEventoIdAndUsuarioIdAndEstado(String eventoId, String usuarioId, EstadoPersonalAcceso estado);
}
