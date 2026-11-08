package com.ticketya.gestionlugares.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.AsientoReservas;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadRequest;

@Service
public class EventoLifecycleService {

    private final EventoService eventoService;
    private final ReservasClient reservasClient;

    public EventoLifecycleService(EventoService eventoService, ReservasClient reservasClient) {
        this.eventoService = eventoService;
        this.reservasClient = reservasClient;
    }

    public Evento publicar(String eventoId, String userId, String userRole) {
        Evento evento = eventoService.validarPublicacion(eventoId, userId, userRole);
        InicializarDisponibilidadRequest request = construirInicializacion(evento);
        invocarReservas("No se pudo inicializar la disponibilidad; el evento permanece en borrador",
                () -> reservasClient.inicializarDisponibilidad(eventoId, request));
        return eventoService.confirmarPublicacion(eventoId, userId, userRole);
    }

    public Evento suspenderVenta(String eventoId, String userId, String userRole) {
        Evento evento = validarAdministracionVenta(eventoId, userId, userRole);
        invocarReservas("No se pudo suspender la venta", () -> reservasClient.cambiarEstadoVenta(
                eventoId, EstadoVentaReservas.SUSPENDIDA));
        return eventoService.suspenderVentaLocal(evento.getId());
    }

    public Evento reactivarVenta(String eventoId, String userId, String userRole) {
        Evento evento = validarAdministracionVenta(eventoId, userId, userRole);
        invocarReservas("No se pudo reactivar la venta", () -> reservasClient.cambiarEstadoVenta(
                eventoId, EstadoVentaReservas.ACTIVA));
        return eventoService.reactivarVentaLocal(evento.getId());
    }

    public Evento cancelar(String eventoId, String userId, String userRole) {
        Evento evento = validarAdministracionVenta(eventoId, userId, userRole);
        if (evento.getEstado() != EstadoEvento.PUBLICADO && evento.getEstado() != EstadoEvento.AGOTADO) {
            throw new ConflictException("Solo se cancela un evento publicado o agotado");
        }
        invocarReservas("No se pudo cancelar la venta", () -> reservasClient.cambiarEstadoVenta(
                eventoId, EstadoVentaReservas.CANCELADA));
        return eventoService.cancelarLocal(evento.getId());
    }

    public Evento aplicarDisponibilidad(String eventoId, boolean agotado) {
        return eventoService.aplicarDisponibilidadLocal(eventoId, agotado);
    }

    public void finalizarVencidos() {
        for (Evento evento : eventoService.buscarPendientesDeFinalizacion(Instant.now())) {
            try {
                invocarReservas("No se pudo finalizar la venta", () -> reservasClient.cambiarEstadoVenta(
                        evento.getId(), EstadoVentaReservas.FINALIZADA));
                eventoService.finalizarLocal(evento.getId());
            } catch (ServiceUnavailableException | ConflictException exception) {
                // El siguiente ciclo reintenta sin alterar el estado local.
            }
        }
    }

    private Evento validarAdministracionVenta(String eventoId, String userId, String userRole) {
        validarAdministrador(userId, userRole);
        Evento evento = eventoService.buscarEvento(eventoId);
        if (evento.getEstado() != EstadoEvento.PUBLICADO && evento.getEstado() != EstadoEvento.AGOTADO) {
            throw new ConflictException("Solo se puede modificar la venta de un evento publicado o agotado");
        }
        return evento;
    }

    private static void validarAdministrador(String userId, String userRole) {
        if (userId == null || userId.isBlank() || userRole == null || userRole.isBlank()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Role");
        }
        if (!"administrador".equalsIgnoreCase(userRole.trim())) {
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
