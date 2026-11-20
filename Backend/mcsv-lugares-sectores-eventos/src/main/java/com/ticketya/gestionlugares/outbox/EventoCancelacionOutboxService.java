package com.ticketya.gestionlugares.outbox;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.filas.http.FilasProperties;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.reservas.http.ReservasProperties;
import com.ticketya.gestionlugares.service.ConflictException;
import com.ticketya.gestionlugares.service.EventoService;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;

@Service
public class EventoCancelacionOutboxService {

    private final EventoService eventoService;
    private final OutboxAvisoRepository outboxRepository;
    private final ComprasProperties comprasProperties;
    private final ReservasProperties reservasProperties;
    private final FilasProperties filasProperties;

    public EventoCancelacionOutboxService(EventoService eventoService, OutboxAvisoRepository outboxRepository,
            ComprasProperties comprasProperties, ReservasProperties reservasProperties, FilasProperties filasProperties) {
        this.eventoService = eventoService;
        this.outboxRepository = outboxRepository;
        this.comprasProperties = comprasProperties;
        this.reservasProperties = reservasProperties;
        this.filasProperties = filasProperties;
    }

    @Transactional
    public void registrarIntencion(String eventoId, String motivo, Instant ahora) {
        eventoService.registrarIntencionCancelacion(eventoId);
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("cancelacion-reservas-" + eventoId);
        aviso.setTipo("CANCELACION_RESERVAS");
        aviso.setEventoId(eventoId);
        aviso.setMotivoCancelacion(motivo);
        aviso.setMetodoHttp("PUT");
        aviso.setDestino(UriComponentsBuilder.fromUri(reservasProperties.getBaseUrl())
                .pathSegment("disponibilidad", "eventos", "{eventoId}", "venta")
                .encode().buildAndExpand(eventoId).toUriString());
        aviso.setPayload(Map.of("estado", "CANCELADA"));
        aviso.setCorrelationId(Correlacion.actual());
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setProximoIntento(ahora);
        aviso.setCreadoEn(ahora);
        aviso.setActualizadoEn(ahora);
        outboxRepository.insert(aviso);
    }

    @Transactional
    public boolean confirmar(AvisoOutbox trabajo, Instant ahora) {
        if (!outboxRepository.confirmarReclamo(trabajo, ahora)) return false;
        Evento evento = eventoService.buscarEvento(trabajo.getEventoId());
        if (!evento.isCancelacionPendiente()) {
            throw new ConflictException("La intención de cancelación ya no está vigente");
        }
        eventoService.cancelarLocal(trabajo.getEventoId(), trabajo.getMotivoCancelacion(), ahora);
        AvisoOutbox compras = CancelacionAvisoOutboxFactory.crear(
                trabajo.getEventoId(), trabajo.getMotivoCancelacion(), ahora, ahora, comprasProperties);
        compras.setCorrelationId(trabajo.getCorrelationId());
        AvisoOutbox fila = CancelacionFilaAvisoOutboxFactory.crear(trabajo.getEventoId(), ahora, filasProperties);
        fila.setCorrelationId(trabajo.getCorrelationId());
        outboxRepository.insert(compras);
        outboxRepository.insert(fila);
        return true;
    }
}
