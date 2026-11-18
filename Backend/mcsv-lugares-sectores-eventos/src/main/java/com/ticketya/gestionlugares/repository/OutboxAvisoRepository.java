package com.ticketya.gestionlugares.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ticketya.gestionlugares.domain.AvisoOutbox;

public interface OutboxAvisoRepository extends MongoRepository<AvisoOutbox, String>, OutboxAvisoRepositoryCustom {
}
