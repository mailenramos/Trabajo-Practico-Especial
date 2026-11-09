package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.EstadoVenta;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;

/** Applies MS1's sales-state notifications using the existing event and reservation lifecycle. */
@Service
public class EstadoVentaService {

    private final EventoDisponibilidadRepository eventosRepository;
    private final ReservaService reservaService;

    public EstadoVentaService(
            EventoDisponibilidadRepository eventosRepository,
            ReservaService reservaService) {
        this.eventosRepository = eventosRepository;
        this.reservaService = reservaService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void aplicar(String eventoId, EstadoVenta estadoSolicitado) {
        EventoDisponibilidad evento = eventosRepository.findByEventoIdForUpdate(eventoId)
                .orElseThrow(() -> new DisponibilidadNoRegistradaException(
                        "No hay disponibilidad registrada en MS2 para el evento solicitado."));

        boolean modificado = switch (estadoSolicitado) {
            case ACTIVA -> activar(evento);
            case SUSPENDIDA -> suspender(evento);
            case CANCELADA -> terminar(evento, EstadoEvento.CANCELADO);
            case FINALIZADA -> terminar(evento, EstadoEvento.FINALIZADO);
        };

        if (modificado) {
            eventosRepository.saveAndFlush(evento);
        }
    }

    private boolean activar(EventoDisponibilidad evento) {
        EstadoEvento estadoActual = evento.getEstadoEvento();
        if (estadoActual == EstadoEvento.PENDIENTE_PUBLICACION) {
            evento.setEstadoEvento(EstadoEvento.PUBLICADO);
        } else if (estadoActual != EstadoEvento.PUBLICADO && estadoActual != EstadoEvento.AGOTADO) {
            throw transicionIncompatible();
        }

        if (evento.isVentaSuspendida()) {
            evento.setVentaSuspendida(false);
            return true;
        }
        return estadoActual == EstadoEvento.PENDIENTE_PUBLICACION;
    }

    private boolean suspender(EventoDisponibilidad evento) {
        EstadoEvento estadoActual = evento.getEstadoEvento();
        if (estadoActual != EstadoEvento.PUBLICADO && estadoActual != EstadoEvento.AGOTADO) {
            throw transicionIncompatible();
        }
        if (!evento.isVentaSuspendida()) {
            evento.setVentaSuspendida(true);
            return true;
        }
        return false;
    }

    private boolean terminar(EventoDisponibilidad evento, EstadoEvento estadoTerminal) {
        EstadoEvento estadoActual = evento.getEstadoEvento();
        if (estadoActual == estadoTerminal) {
            return false;
        }
        if (estadoActual == EstadoEvento.CANCELADO || estadoActual == EstadoEvento.FINALIZADO) {
            throw transicionIncompatible();
        }

        // The caller holds the event row lock. Reservation creation takes this
        // same lock first, so the candidate set is stable against new holds.
        // Each reservation is then locked and rechecked to serialize with its
        // confirmation, manual release, and expiration.
        reservaService.liberarReservasVigentesDelEvento(evento.getEventoId());
        evento.setEstadoEvento(estadoTerminal);
        return true;
    }

    private ReservaConflictException transicionIncompatible() {
        return new ReservaConflictException("La transición de ventas no es compatible con el estado actual.");
    }
}
