package com.ticketya.gestionlugares.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketya.gestionlugares.api.CreateEventoRequest;
import com.ticketya.gestionlugares.api.ModificarEventoRequest;
import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.outbox.ModificacionAvisoOutboxFactory;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.security.UserRoles;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;

@Service
public class EventoService {

    private static final int LIMITE_ENTRADAS_POR_DEFECTO = 4;

    private final EventoRepository eventoRepository;
    private final LugarRepository lugarRepository;
    private final CatalogoCacheInvalidador catalogoCacheInvalidador;
    private final OutboxAvisoRepository outboxRepository;
    private final ComprasProperties comprasProperties;
    private final Clock outboxClock;

    public EventoService(EventoRepository eventoRepository, LugarRepository lugarRepository,
            CatalogoCacheInvalidador catalogoCacheInvalidador, OutboxAvisoRepository outboxRepository,
            ComprasProperties comprasProperties, Clock outboxClock) {
        this.eventoRepository = eventoRepository;
        this.lugarRepository = lugarRepository;
        this.catalogoCacheInvalidador = catalogoCacheInvalidador;
        this.outboxRepository = outboxRepository;
        this.comprasProperties = comprasProperties;
        this.outboxClock = outboxClock;
    }

    public Evento crearEvento(CreateEventoRequest request, String organizadorId, String userRolesHeader) {
        validarIdentidadOrganizador(organizadorId, UserRoles.fromHeader(userRolesHeader));
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

    @Transactional
    public Evento modificarEvento(String eventoId, ModificarEventoRequest request, String userId,
            String userRolesHeader) {
        UserRoles roles = UserRoles.fromHeader(userRolesHeader);
        if (userId == null || userId.isBlank() || roles.isEmpty()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Roles");
        }
        if (!roles.containsAny("ORGANIZADOR", "ADMIN")) {
            throw new ForbiddenException("Se requiere ORGANIZADOR o ADMIN");
        }
        Evento evento = buscarEvento(eventoId);
        if (!roles.contains("ADMIN") && !Objects.equals(evento.getOrganizadorId(), userId)) {
            throw new ForbiddenException("El evento pertenece a otro organizador");
        }
        if (evento.getEstado() == EstadoEvento.CANCELADO || evento.getEstado() == EstadoEvento.FINALIZADO) {
            throw new ConflictException("No se puede modificar un evento cancelado o finalizado");
        }
        validarSinCancelacionPendiente(evento);
        if (request.fechaHora() == null && request.lugarId() == null) {
            throw new IllegalArgumentException("Se requiere fechaHora o lugarId");
        }

        Instant fechaHora = request.fechaHora() == null ? evento.getFechaHora() : parsearFechaHora(request.fechaHora());
        Lugar lugar = null;
        if (request.lugarId() != null) {
            if (request.lugarId().isBlank()) throw new IllegalArgumentException("El lugarId no puede estar vacío");
            lugar = lugarRepository.findById(request.lugarId())
                    .orElseThrow(() -> new IllegalArgumentException("No existe el lugar " + request.lugarId()));
        }
        boolean cambiaFecha = !Objects.equals(fechaHora, evento.getFechaHora());
        boolean cambiaLugar = lugar != null && !Objects.equals(lugar.getId(), evento.getLugarId());
        if (!cambiaFecha && !cambiaLugar) return evento;

        evento.setFechaHora(fechaHora);
        if (cambiaLugar) {
            evento.setLugarId(lugar.getId());
            evento.setCiudad(lugar.getCiudad());
        }
        Evento guardado = guardarCambioCatalogo(evento);
        if (guardado.getEstado() == EstadoEvento.PUBLICADO || guardado.getEstado() == EstadoEvento.AGOTADO) {
            Lugar lugarActual = lugar != null ? lugar : lugarRepository.findById(guardado.getLugarId())
                    .orElseThrow(() -> new ResourceNotFoundException("No existe el lugar del evento"));
            AvisoOutbox aviso = ModificacionAvisoOutboxFactory.crear(
                    guardado, lugarActual, outboxClock.instant(), comprasProperties);
            aviso.setCorrelationId(Correlacion.actual());
            outboxRepository.insert(aviso);
        }
        return guardado;
    }

    private static Instant parsearFechaHora(String valor) {
        try {
            OffsetDateTime fecha = OffsetDateTime.parse(valor);
            if (!fecha.getOffset().equals(ZoneOffset.UTC)) {
                throw new IllegalArgumentException("La fecha y hora debe estar expresada en UTC");
            }
            Instant fechaHora = fecha.toInstant().truncatedTo(ChronoUnit.MILLIS);
            if (!fechaHora.isAfter(Instant.now())) {
                throw new IllegalArgumentException("La fecha y hora de inicio debe ser futura");
            }
            return fechaHora;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("La fecha y hora debe ser ISO-8601 UTC", exception);
        }
    }

    public Evento asignarPrecio(String eventoId, String sectorId, BigDecimal precio, String userId,
            String userRolesHeader) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, UserRoles.fromHeader(userRolesHeader));
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

    public Evento actualizarLimiteEntradas(String eventoId, int limite, String userId, String userRolesHeader) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, UserRoles.fromHeader(userRolesHeader));
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

    public Evento actualizarPoliticaFila(Evento evento, boolean requiereFila) {
        if (requiereFila && evento.getEstado() == EstadoEvento.CANCELADO) {
            throw new ConflictException("No se puede activar la fila de un evento cancelado");
        }
        if (evento.isRequiereFila() == requiereFila) return evento;
        evento.setRequiereFila(requiereFila);
        return guardar(evento);
    }

    /** Invocado por la orquestación de publicación después de que Reservas confirme la inicialización. */
    public Evento validarPublicacion(String eventoId, String userId, String userRolesHeader) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, UserRoles.fromHeader(userRolesHeader));
        if (evento.getEstado() != EstadoEvento.BORRADOR) {
            throw new ConflictException("Solo se puede publicar un evento en borrador");
        }
        if (evento.getSectores().isEmpty()
                || evento.getSectores().stream().anyMatch(sector -> sector.getPrecio() == null)) {
            throw new IllegalArgumentException("Todos los sectores deben tener precio para publicar");
        }
        return evento;
    }

    public Evento confirmarPublicacion(String eventoId, String userId, String userRolesHeader) {
        Evento evento = buscarEvento(eventoId);
        validarDuenoOrganizador(evento, userId, UserRoles.fromHeader(userRolesHeader));
        evento.definirPrecioMinimoYPublicar();
        return guardarCambioCatalogo(evento);
    }

    /** Transición local para ser invocada solo después de la confirmación correspondiente de Reservas. */
    public Evento suspenderVentaLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        validarSinCancelacionPendiente(evento);
        evento.suspenderVenta();
        return guardarCambioCatalogo(evento);
    }

    public Evento reactivarVentaLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        validarSinCancelacionPendiente(evento);
        evento.reactivarVenta();
        return guardarCambioCatalogo(evento);
    }

    public Evento cancelarLocal(String eventoId, String motivo, Instant canceladoEn) {
        Evento evento = buscarEvento(eventoId);
        evento.cancelar(motivo, canceladoEn);
        evento.setCancelacionPendiente(false);
        return guardarCambioCatalogo(evento);
    }

    public Evento cancelarLocal(String eventoId) {
        return cancelarLocal(eventoId, "Cancelación del evento por el administrador", Instant.now());
    }

    public Evento finalizarLocal(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        validarSinCancelacionPendiente(evento);
        evento.finalizar();
        return guardarCambioCatalogo(evento);
    }

    public Evento aplicarDisponibilidadLocal(String eventoId, boolean agotado) {
        Evento evento = buscarEvento(eventoId);
        evento.aplicarDisponibilidad(agotado);
        return guardarCambioCatalogo(evento);
    }

    public List<Evento> buscarPendientesDeFinalizacion(Instant ahora) {
        return eventoRepository.findByEstadoInAndFechaHoraBefore(
                List.of(EstadoEvento.PUBLICADO, EstadoEvento.AGOTADO), ahora);
    }

    public Evento registrarIntencionCancelacion(String eventoId) {
        Evento evento = buscarEvento(eventoId);
        if (evento.isCancelacionPendiente()) {
            throw new ServiceUnavailableException("La cancelación está pendiente");
        }
        if (evento.getEstado() != EstadoEvento.PUBLICADO && evento.getEstado() != EstadoEvento.AGOTADO) {
            throw new ConflictException("Solo se cancela un evento publicado o agotado");
        }
        evento.setCancelacionPendiente(true);
        return guardar(evento);
    }

    public static void validarSinCancelacionPendiente(Evento evento) {
        if (evento.isCancelacionPendiente()) {
            throw new ConflictException("El evento tiene una cancelación pendiente");
        }
    }

    private Evento guardarCambioCatalogo(Evento evento) {
        Evento guardado = guardar(evento);
        catalogoCacheInvalidador.invalidar();
        return guardado;
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

    private static void validarIdentidadOrganizador(String userId, UserRoles roles) {
        if (userId == null || userId.isBlank() || roles.isEmpty()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Roles");
        }
        if (!roles.contains("ORGANIZADOR")) {
            throw new ForbiddenException("Solo un organizador puede gestionar sus eventos");
        }
    }

    private static void validarDuenoOrganizador(Evento evento, String userId, UserRoles roles) {
        validarIdentidadOrganizador(userId, roles);
        if (!evento.getOrganizadorId().equals(userId)) {
            throw new ForbiddenException("El evento pertenece a otro organizador");
        }
    }
}
