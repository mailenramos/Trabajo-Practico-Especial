package com.ticketya.gestionlugares.repository;

import java.time.Instant;
import java.util.Optional;

import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;

public interface PersonalAccesoRepositoryCustom {

    Optional<AsignacionPersonalAcceso> revocarSiActiva(String eventoId, String usuarioId, Instant revocadaEn);

    Optional<AsignacionPersonalAcceso> reactivarSiRevocada(String eventoId, String usuarioId, Instant asignadaEn);
}
