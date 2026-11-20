package com.ticketya.gestionlugares.repository;

import java.time.Instant;
import java.util.Optional;

import com.ticketya.gestionlugares.domain.AvisoOutbox;

public interface OutboxAvisoRepositoryCustom {

    Optional<AvisoOutbox> reclamarSiguiente(Instant ahora, Instant leaseHasta, int maxIntentos);

    Optional<AvisoOutbox> reclamarPorId(String avisoId, Instant ahora, Instant leaseHasta, int maxIntentos);

    boolean confirmarReclamo(AvisoOutbox aviso, Instant ahora);

    boolean guardarResultadoReclamo(AvisoOutbox aviso, Instant leaseHasta, Instant ahora);

    long marcarAvisosAgotados(Instant ahora, int maxIntentos);
}
