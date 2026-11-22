package com.ticketya.gestionlugares.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.ReservasConflictException;
import com.ticketya.gestionlugares.reservas.ReservasSincrono;
import com.ticketya.gestionlugares.reservas.dto.AsientoReservas;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadRequest;
import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxService;
import com.ticketya.gestionlugares.outbox.EventoPublicacionOutboxService;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.security.UserRoles;

@Service
public class EventoLifecycleService {

    private static final String MOTIVO_CANCELACION_POR_DEFECTO = "Cancelación del evento por el administrador";

    private final EventoService eventoService;
    private final ReservasClient reservasClient;
    private final ReservasSincrono reservasSincrono;
    private final EventoCancelacionOutboxService eventoCancelacionOutboxService;
    private final EventoPublicacionOutboxService eventoPublicacionOutboxService;
    private final OutboxDispatcher outboxDispatcher;

    public EventoLifecycleService(EventoService eventoService, ReservasClient reservasClient,
            EventoCancelacionOutboxService eventoCancelacionOutboxService,
            EventoPublicacionOutboxService eventoPublicacionOutboxService, OutboxDispatcher outboxDispatcher,
            ReservasSincrono reservasSincrono) {
        this.eventoService = eventoService;
        this.reservasClient = reservasClient;
        this.reservasSincrono = reservasSincrono;
        this.eventoCancelacionOutboxService = eventoCancelacionOutboxService;
        this.eventoPublicacionOutboxService = eventoPublicacionOutboxService;
        this.outboxDispatcher = outboxDispatcher;
    }

    public Evento publicar(String eventoId, String userId, String userRolesHeader) {
        Evento evento = eventoService.validarPublicacion(eventoId, userId, userRolesHeader);
        InicializarDisponibilidadRequest request = construirInicializacion(evento);
        invocarReservas("No se pudo inicializar la disponibilidad; el evento permanece en borrador",
                () -> reservasSincrono.ejecutar(() -> reservasClient.inicializarDisponibilidad(eventoId, request)));
        invocarReservas("No se pudo activar la venta en Reservas; el evento permanece en borrador",
                () -> reservasSincrono.ejecutar(() -> reservasClient.cambiarEstadoVenta(eventoId, EstadoVentaReservas.ACTIVA)));
        Evento publicado = eventoPublicacionOutboxService.publicarConAvisoOutbox(
                eventoId, userId, userRolesHeader);
        outboxDispatcher.procesarAviso("publicacion-" + publicado.getId());
        return publicado;
    }

    public Evento suspenderVenta(String eventoId, String userId, String userRolesHeader) {
        Evento evento = validarAdministracionVentaSinCancelacion(eventoId, userId, userRolesHeader);
        invocarReservasVentaAdministrativa("No se pudo suspender la venta", () -> reservasSincrono.ejecutar(
                () -> reservasClient.cambiarEstadoVenta(eventoId, EstadoVentaReservas.SUSPENDIDA)));
        return eventoService.suspenderVentaLocal(evento.getId());
    }

    public Evento reactivarVenta(String eventoId, String userId, String userRolesHeader) {
        Evento evento = validarAdministracionVentaSinCancelacion(eventoId, userId, userRolesHeader);
        invocarReservasVentaAdministrativa("No se pudo reactivar la venta", () -> reservasSincrono.ejecutar(
                () -> reservasClient.cambiarEstadoVenta(eventoId, EstadoVentaReservas.ACTIVA)));
        return eventoService.reactivarVentaLocal(evento.getId());
    }

    public Evento cancelar(String eventoId, String userId, String userRolesHeader) {
        return cancelar(eventoId, null, userId, userRolesHeader);
    }

    public Evento cancelar(String eventoId, String motivo, String userId, String userRolesHeader) {
        Evento evento = validarAdministracionVenta(eventoId, userId, userRolesHeader);
        if (evento.isCancelacionPendiente()) {
            throw new ServiceUnavailableException("La cancelación está pendiente");
        }
        String motivoEfectivo = motivo == null || motivo.isBlank()
                ? MOTIVO_CANCELACION_POR_DEFECTO : motivo.trim();
        try {
            eventoCancelacionOutboxService.registrarIntencion(evento.getId(), motivoEfectivo, Instant.now());
        } catch (ConflictException exception) {
            Evento actual = eventoService.buscarEvento(eventoId);
            if (actual.getEstado() == EstadoEvento.CANCELADO || actual.getEstado() == EstadoEvento.FINALIZADO) {
                throw exception;
            }
            throw new ServiceUnavailableException("No se pudo registrar la intención de cancelación", exception);
        } catch (DataAccessException | TransactionException exception) {
            throw new ServiceUnavailableException("No se pudo registrar la intención de cancelación", exception);
        }
        try {
            if (outboxDispatcher.procesarCancelacion("cancelacion-reservas-" + evento.getId())) {
                Evento resultado = eventoService.buscarEvento(eventoId);
                if (resultado.getEstado() == EstadoEvento.CANCELADO) return resultado;
            }
        } catch (DataAccessException | TransactionException exception) {
            throw new ServiceUnavailableException("No se pudo confirmar la cancelación", exception);
        }
        throw new ServiceUnavailableException("La cancelación está pendiente");
    }

    public Evento aplicarDisponibilidad(String eventoId, boolean agotado) {
        return eventoService.aplicarDisponibilidadLocal(eventoId, agotado);
    }

    public void finalizarVencidos() {
        for (Evento evento : eventoService.buscarPendientesDeFinalizacion(Instant.now())) {
            if (evento.isCancelacionPendiente()) continue;
            try {
                invocarReservas("No se pudo finalizar la venta", () -> reservasClient.cambiarEstadoVenta(
                        evento.getId(), EstadoVentaReservas.FINALIZADA));
                eventoService.finalizarLocal(evento.getId());
            } catch (ServiceUnavailableException | ConflictException exception) {
                // El siguiente ciclo reintenta sin alterar el estado local.
            }
        }
    }

    private Evento validarAdministracionVenta(String eventoId, String userId, String userRolesHeader) {
        validarAdministrador(userId, UserRoles.fromHeader(userRolesHeader));
        Evento evento = eventoService.buscarEvento(eventoId);
        if (evento.getEstado() != EstadoEvento.PUBLICADO && evento.getEstado() != EstadoEvento.AGOTADO) {
            throw new ConflictException("Solo se puede modificar la venta de un evento publicado o agotado");
        }
        return evento;
    }

    private Evento validarAdministracionVentaSinCancelacion(String eventoId, String userId, String userRolesHeader) {
        Evento evento = validarAdministracionVenta(eventoId, userId, userRolesHeader);
        EventoService.validarSinCancelacionPendiente(evento);
        return evento;
    }

    private static void validarAdministrador(String userId, UserRoles roles) {
        if (userId == null || userId.isBlank() || roles.isEmpty()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Roles");
        }
        if (!roles.contains("ADMIN")) {
            throw new ForbiddenException("Solo un administrador puede gestionar la venta de eventos");
        }
    }

    private static InicializarDisponibilidadRequest construirInicializacion(Evento evento) {
        List<SectorDisponibilidadRequest> sectores = evento.getSectores().stream()
                .map(EventoLifecycleService::construirSector)
                .toList();
        return new InicializarDisponibilidadRequest(evento.getLimiteEntradasPorCliente(), sectores);
    }

    private static SectorDisponibilidadRequest construirSector(EventoSector sector) {
        if (sector.getTipo() == TipoSector.NUMERADO) {
            int filas = sector.getFilas();
            int asientosPorFila = sector.getAsientosPorFila();
            int capacidad = Math.multiplyExact(filas, asientosPorFila);
            List<AsientoReservas> asientos = new ArrayList<>(capacidad);
            for (int fila = 1; fila <= filas; fila++) {
                for (int numero = 1; numero <= asientosPorFila; numero++) {
                    asientos.add(new AsientoReservas(fila, numero));
                }
            }
            return new SectorDisponibilidadRequest(sector.getSectorId(), sector.getTipo(), capacidad, asientos);
        }
        return new SectorDisponibilidadRequest(sector.getSectorId(), sector.getTipo(),
                sector.getCapacidad(), List.of());
    }

    private static void invocarReservasVentaAdministrativa(String mensaje, Runnable accion) {
        try {
            accion.run();
        } catch (ReservasConflictException exception) {
            throw new ConflictException(mensaje + ": conflicto en Reservas");
        } catch (ReservasClientException exception) {
            throw new ServiceUnavailableException(mensaje, exception);
        }
    }

    private static void invocarReservas(String mensaje, Runnable accion) {
        try {
            accion.run();
        } catch (ReservasClientException exception) {
            throw new ServiceUnavailableException(mensaje, exception);
        }
    }

    @FunctionalInterface
    private interface Runnable {
        void run();
    }
}
