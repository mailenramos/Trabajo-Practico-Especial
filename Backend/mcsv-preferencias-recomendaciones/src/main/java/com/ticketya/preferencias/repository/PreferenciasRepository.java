package com.ticketya.preferencias.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ticketya.preferencias.domain.Preferencia;

public interface PreferenciasRepository extends MongoRepository<Preferencia, String> {
}
