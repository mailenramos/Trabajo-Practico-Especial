package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

@Service
public class CantidadClienteEventoService {

    private final CantidadClienteEventoRepository cantidadesRepository;
    private final EventoDisponibilidadRepository eventosRepository;
    private final EntityManager entityManager;

    public CantidadClienteEventoService(CantidadClienteEventoRepository cantidadesRepository,
            EventoDisponibilidadRepository eventosRepository,
            EntityManager entityManager) {
        this.cantidadesRepository = cantidadesRepository;
        this.eventosRepository = eventosRepository;
        this.entityManager = entityManager;
    }

    /**
     * Returns the aggregate for the key, creating its zero-valued row once if absent.
     * The event row serializes the first creation for each event; the second aggregate
     * lookup is a locking read so it sees a row committed while this call waited.
     */
    @Transactional
    public CantidadClienteEvento obtenerOCrear(String usuarioId, String eventoId) {
        if (usuarioId == null || eventoId == null) {
            throw new IllegalArgumentException("usuarioId y eventoId son obligatorios");
        }

        if (cantidadesRepository.countByUsuarioIdAndEventoId(usuarioId, eventoId) > 0) {
            CantidadClienteEvento existenteAdministrado = cantidadesRepository
                    .findByUsuarioIdAndEventoIdForUpdate(usuarioId, eventoId)
                    .orElseThrow(() -> new IllegalStateException(
                            "El agregado cliente/evento desapareció durante su bloqueo"));
            Long existenteId = existenteAdministrado.getId();
            entityManager.detach(existenteAdministrado);
            CantidadClienteEvento actualizado = entityManager.find(
                    CantidadClienteEvento.class, existenteId, LockModeType.PESSIMISTIC_WRITE);
            if (actualizado == null) {
                throw new IllegalStateException("El agregado cliente/evento desapareció durante su bloqueo");
            }
            return actualizado;
        }

        eventosRepository.findByEventoIdForUpdate(eventoId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No existe el evento inicializado " + eventoId));

        var existenteTrasBloqueo = cantidadesRepository.findByUsuarioIdAndEventoIdForUpdate(usuarioId, eventoId);
        if (existenteTrasBloqueo.isPresent()) {
            return existenteTrasBloqueo.get();
        }

        return cantidadesRepository.saveAndFlush(new CantidadClienteEvento(usuarioId, eventoId));
    }
}
