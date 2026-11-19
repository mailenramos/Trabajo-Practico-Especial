package com.ticketya.gestionlugares.outbox;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.preferencias.http.PreferenciasProperties;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.service.EventoService;

@Service
public class EventoPublicacionOutboxService {

    private final EventoService eventoService;
    private final OutboxAvisoRepository outboxRepository;
    private final PreferenciasProperties preferenciasProperties;
    private final Clock clock;

    public EventoPublicacionOutboxService(EventoService eventoService, OutboxAvisoRepository outboxRepository,
            PreferenciasProperties preferenciasProperties, Clock outboxClock) {
        this.eventoService = eventoService;
        this.outboxRepository = outboxRepository;
        this.preferenciasProperties = preferenciasProperties;
        this.clock = outboxClock;
    }

    @Transactional
    public Evento publicarConAvisoOutbox(String eventoId, String userId, String userRolesHeader) {
        Evento publicado = eventoService.confirmarPublicacion(eventoId, userId, userRolesHeader);
        outboxRepository.insert(PublicacionAvisoOutboxFactory.crear(
                publicado, clock.instant(), preferenciasProperties));
        return publicado;
    }
}
