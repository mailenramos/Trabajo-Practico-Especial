package ar.edu.unicen.ms_fila_virtual.service;

import ar.edu.unicen.ms_fila_virtual.dto.ConfiguracionFilaRequest;
import ar.edu.unicen.ms_fila_virtual.dto.FilaEventoResponse;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import org.springframework.stereotype.Service;

/** Coordinates HTTP outside local transaction boundaries; repeated PUTs are idempotent. */
@Service
public class FilaCoordinacionService {
    private final Ms1EventoClient ms1;
    private final FilaVirtualService local;

    public FilaCoordinacionService(Ms1EventoClient ms1, FilaVirtualService local) {
        this.ms1 = ms1;
        this.local = local;
    }

    public void verificarIngreso(String eventoId) {
        Ms1EventoClient.Evento evento = consultar(eventoId);
        exigirNoTerminado(evento);
        if (evento.requiereFila() == null) throw new Ms1NoDisponibleException("MS1 no informó la política de fila del evento.");
        if (!evento.requiereFila()) throw new FilaConflictException("El evento no requiere fila virtual. Puede reservar directamente.");
    }

    public FilaEventoResponse consultarFila(String eventoId, String usuarioId, boolean admin) {
        autorizar(consultar(eventoId), usuarioId, admin);
        return local.consultarFila(eventoId);
    }

    public void configurar(String eventoId, String usuarioId, boolean admin, ConfiguracionFilaRequest request) {
        Ms1EventoClient.Evento evento = consultar(eventoId);
        autorizar(evento, usuarioId, admin);
        exigirNoTerminado(evento);
        local.configurar(eventoId, request);
    }

    public void cambiarEstado(String eventoId, String usuarioId, boolean admin, EstadoFila estado) {
        if (estado == null) throw new IllegalArgumentException("El estado es obligatorio.");
        Ms1EventoClient.Evento evento = consultar(eventoId);
        autorizar(evento, usuarioId, admin);
        exigirNoTerminado(evento);
        switch (estado) {
            case ACTIVA -> {
                EstadoFila estadoLocal = local.prepararFila(eventoId);
                if (estadoLocal == EstadoFila.PAUSADA) {
                    if (evento.requiereFila() == null) throw new Ms1NoDisponibleException("MS1 no informó la política de fila del evento.");
                    if (!evento.requiereFila()) throw new FilaConflictException("No se puede reanudar: el evento no requiere fila.");
                } else if (estadoLocal != EstadoFila.ACTIVA || !Boolean.TRUE.equals(evento.requiereFila())) {
                    ms1.actualizarPolitica(eventoId, true);
                }
                local.cambiarEstadoLocal(eventoId, EstadoFila.ACTIVA);
            }
            case DESACTIVADA -> {
                local.cambiarEstadoLocal(eventoId, EstadoFila.DESACTIVADA);
                ms1.actualizarPolitica(eventoId, false);
            }
            case PAUSADA -> local.cambiarEstadoLocal(eventoId, EstadoFila.PAUSADA);
        }
    }

    private Ms1EventoClient.Evento consultar(String eventoId) {
        if (eventoId == null || eventoId.isBlank() || eventoId.length() > 255) throw new IllegalArgumentException("eventoId inválido.");
        return ms1.consultar(eventoId);
    }

    private void autorizar(Ms1EventoClient.Evento evento, String usuarioId, boolean admin) {
        if (admin) return;
        if (evento.organizadorId() == null || evento.organizadorId().isEmpty())
            throw new Ms1NoDisponibleException("MS1 no informó el organizador del evento.");
        if (!evento.organizadorId().equals(usuarioId)) throw new PermisoFilaException("El evento pertenece a otro organizador.");
    }

    private void exigirNoTerminado(Ms1EventoClient.Evento evento) {
        if (evento.estado().equals("cancelado") || evento.estado().equals("finalizado"))
            throw new FilaConflictException("El evento está cancelado o finalizado.");
    }
}
