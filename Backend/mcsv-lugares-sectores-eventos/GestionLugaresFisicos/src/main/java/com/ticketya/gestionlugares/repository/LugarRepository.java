package com.ticketya.gestionlugares.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ticketya.gestionlugares.domain.Lugar;

public interface LugarRepository extends MongoRepository<Lugar, String> {
}
