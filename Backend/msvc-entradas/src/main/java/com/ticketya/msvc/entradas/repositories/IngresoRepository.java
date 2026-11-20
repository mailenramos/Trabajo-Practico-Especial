package com.ticketya.msvc.entradas.repositories;

import com.ticketya.msvc.entradas.entities.Ingreso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IngresoRepository extends JpaRepository<Ingreso, UUID> {

    Optional<Ingreso> findByIdEntrada(UUID idEntrada);
}
