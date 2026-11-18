package com.ticketya.gestionlugares.outbox;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketya.gestionlugares.compras.EventoCanceladoRequest;
import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.service.EventoService;

@Service
public class EventoCancelacionOutboxService {

    private static final String DESTINO_CANCELACION_COMPRAS = "/eventos-cancelados";

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
        EventoCanceladoRequest request = new EventoCanceladoRequest(
                eventoId, motivo, canceladoEn.toString());
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("cancelacion-" + eventoId);
        aviso.setTipo("CANCELACION");
        aviso.setEventoId(eventoId);
        aviso.setDestino(comprasProperties.getBaseUrl().resolve(DESTINO_CANCELACION_COMPRAS).toString());
        aviso.setPayload(Map.of(
                "eventoId", request.eventoId(),
                "motivo", request.motivo(),
                "canceladoEn", request.canceladoEn()));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(canceladoEn);
        aviso.setCreadoEn(canceladoEn);
        aviso.setActualizadoEn(canceladoEn);
        outboxRepository.insert(aviso);
        return cancelado;
    }
}
