package com.ticketya.gestionlugares.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.ticketya.gestionlugares.api.CreateEventoRequest;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;

@Service
public class EventoService {

    private static final int LIMITE_ENTRADAS_POR_DEFECTO = 4;

    private final EventoRepository eventoRepository;
    private final LugarRepository lugarRepository;

    public EventoService(EventoRepository eventoRepository, LugarRepository lugarRepository) {
        this.eventoRepository = eventoRepository;
        this.lugarRepository = lugarRepository;
    }

    public Evento crearEvento(CreateEventoRequest request, String organizadorId, String rol) {
        validarIdentidadOrganizador(organizadorId, rol);
        if (request.fechaHora() == null || !request.fechaHora().isAfter(Instant.now())) {
            throw new IllegalArgumentException("La fecha y hora de inicio debe ser futura");
        }
        Lugar lugar = lugarRepository.findById(request.lugarId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe el lugar " + request.lugarId()));
        int limite = request.limiteEntradasPorCliente() == null
                ? LIMITE_ENTRADAS_POR_DEFECTO : request.limiteEntradasPorCliente();
        if (limite < 1) throw new IllegalArgumentException("El límite debe ser mayor o igual a 1");
        List<EventoSector> sectores = lugar.getSectores().stream()
                .map(EventoService::copiarSector)
                .toList();
        Evento evento = Evento.borrador(organizadorId, lugar.getId(), lugar.getCiudad(),
                request.nombre().trim(), request.descripcion().trim(), request.tipo().trim(),
                request.participantes().stream().map(String::trim).toList(), request.fechaHora(), limite, sectores);
        return eventoRepository.save(evento);
    }

    public Evento asignarPrecio(String eventoId, String sectorId, BigDecimal precio, String userId, String userRole) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, userRole);
        if (evento.getEstado() != EstadoEvento.BORRADOR) {
            throw new ConflictException("Los precios no se pueden modificar después de publicar");
        }
        if (precio == null || precio.signum() < 0) throw new IllegalArgumentException("El precio debe ser mayor o igual a cero");
        Lugar lugar = lugarRepository.findById(evento.getLugarId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe el lugar del evento"));
        boolean perteneceAlLugar = lugar.getSectores().stream()
                .anyMatch(sector -> sectorId.equals(sector.id()));
        if (!perteneceAlLugar || evento.buscarSector(sectorId) == null) {
            throw new ResourceNotFoundException("El sector " + sectorId + " no pertenece al lugar del evento");
        }
        evento.fijarPrecio(sectorId, precio);
        return guardar(evento);
    }

    public Evento actualizarLimiteEntradas(String eventoId, int limite, String userId, String userRole) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, userRole);
        if (evento.getEstado() != EstadoEvento.BORRADOR) {
            throw new ConflictException("El límite no se puede modificar después de publicar");
        }
        if (limite < 1) throw new IllegalArgumentException("El límite debe ser mayor o igual a 1");
        evento.actualizarLimiteEntradasPorCliente(limite);
        return guardar(evento);
    }

    public Evento buscarEvento(String eventoId) {
        return eventoRepository.findById(eventoId)
                .orElseThrow(() -> new ResourceNotFoundException("No existe el evento " + eventoId));
    }

    /** Invocado por la orquestación de publicación después de que Reservas confirme la inicialización. */
    public Evento validarPublicacion(String eventoId, String userId, String userRole) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, userRole);
        if (evento.getEstado() != EstadoEvento.BORRADOR) {
            throw new ConflictException("Solo se puede publicar un evento en borrador");
        }
        if (evento.getSectores().isEmpty()
                || evento.getSectores().stream().anyMatch(sector -> sector.getPrecio() == null)) {
            throw new IllegalArgumentException("Todos los sectores deben tener precio para publicar");
        }
        return evento;
    }

    public Evento confirmarPublicacion(String eventoId, String userId, String userRole) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, userRole);
        evento.definirPrecioMinimoYPublicar();
        return guardar(evento);
    }

    /** Transición local para ser invocada solo después de la confirmación correspondiente de Reservas. */
    public Evento suspenderVentaLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        evento.suspenderVenta();
        return guardar(evento);
    }

    public Evento reactivarVentaLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        evento.reactivarVenta();
        return guardar(evento);
    }

    public Evento cancelarLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        evento.cancelar();
        return guardar(evento);
    }

    public Evento finalizarLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        evento.finalizar();
        return guardar(evento);
    }

    public Evento aplicarDisponibilidadLocal(String eventoId, boolean agotado) {
        Evento evento = buscarEvento(eventoId);
        evento.aplicarDisponibilidad(agotado);
        return guardar(evento);
    }

    public List<Evento> buscarPendientesDeFinalizacion(Instant ahora) {
        return eventoRepository.findByEstadoInAndFechaHoraBefore(
                List.of(EstadoEvento.PUBLICADO, EstadoEvento.AGOTADO), ahora);
    }

    private Evento guardar(Evento evento) {
        try {
            return eventoRepository.save(evento);
        } catch (OptimisticLockingFailureException exception) {
            throw new ConflictException("El evento cambió en paralelo; vuelva a intentar la operación");
        }
    }

    private static EventoSector copiarSector(Sector sector) {
        return new EventoSector(sector.id(), sector.nombre(), sector.tipo(), sector.filas(),
                sector.asientosPorFila(), sector.capacidad(), null);
    }

    private static void validarIdentidadOrganizador(String userId, String userRole) {
        if (userId == null || userId.isBlank() || userRole == null || userRole.isBlank()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Role");
        }
        if (!"organizador".equalsIgnoreCase(userRole.trim())) {
            throw new ForbiddenException("Solo un organizador puede gestionar sus eventos");
        }
    }

    private static void validarDuenoOrganizador(Evento evento, String userId, String userRole) {
        validarIdentidadOrganizador(userId, userRole);
        if (!evento.getOrganizadorId().equals(userId)) {
            throw new ForbiddenException("El evento pertenece a otro organizador");
        }
    }
}
