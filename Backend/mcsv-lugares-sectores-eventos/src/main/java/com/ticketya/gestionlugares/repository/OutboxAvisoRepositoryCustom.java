package com.ticketya.gestionlugares.repository;

import java.time.Instant;
import java.util.Optional;

import com.ticketya.gestionlugares.domain.AvisoOutbox;

public interface OutboxAvisoRepositoryCustom {

    Optional<AvisoOutbox> reclamarSiguiente(Instant ahora, Instant leaseHasta, int maxIntentos);
}
