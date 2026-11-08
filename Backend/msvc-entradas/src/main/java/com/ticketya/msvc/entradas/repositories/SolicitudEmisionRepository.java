package com.ticketya.msvc.entradas.repositories;

import com.ticketya.msvc.entradas.entities.SolicitudEmision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SolicitudEmisionRepository extends JpaRepository<SolicitudEmision, UUID> {

    Optional<SolicitudEmision> findByIdCompra(UUID idCompra);
}
