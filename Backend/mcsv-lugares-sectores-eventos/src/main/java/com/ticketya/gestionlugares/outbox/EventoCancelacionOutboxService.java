package com.ticketya.gestionlugares.outbox;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.service.EventoService;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;

@Service
public class EventoCancelacionOutboxService {

    private final EventoService eventoService;
    private final OutboxAvisoRepository outboxRepository;
    private final ComprasProperties comprasProperties;

    public EventoCancelacionOutboxService(EventoService eventoService, OutboxAvisoRepository outboxRepository,
            ComprasProperties comprasProperties) {
        this.eventoService = eventoService;
        this.outboxRepository = outboxRepository;
        this.comprasProperties = comprasProperties;
    }

    @Transactional
    public Evento cancelarConAvisoOutbox(String eventoId, String motivo, Instant canceladoEn) {
        Evento cancelado = eventoService.cancelarLocal(eventoId, motivo, canceladoEn);
        AvisoOutbox aviso = CancelacionAvisoOutboxFactory.crear(
                eventoId, motivo, canceladoEn, canceladoEn, comprasProperties);
        aviso.setCorrelationId(Correlacion.actual());
        outboxRepository.insert(aviso);
        return cancelado;
    }
}
