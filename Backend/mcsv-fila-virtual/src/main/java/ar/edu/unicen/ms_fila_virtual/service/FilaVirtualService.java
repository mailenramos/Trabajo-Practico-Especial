package ar.edu.unicen.ms_fila_virtual.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_fila_virtual.dto.ConfiguracionFilaRequest;
import ar.edu.unicen.ms_fila_virtual.dto.ConsumirTurnoRequest;
import ar.edu.unicen.ms_fila_virtual.dto.ConsumoTurnoResponse;
import ar.edu.unicen.ms_fila_virtual.dto.FilaEventoResponse;
import ar.edu.unicen.ms_fila_virtual.dto.TurnoResponse;
import ar.edu.unicen.ms_fila_virtual.model.FilaEvento;
import ar.edu.unicen.ms_fila_virtual.model.Turno;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;
import ar.edu.unicen.ms_fila_virtual.repository.FilaEventoRepository;
import ar.edu.unicen.ms_fila_virtual.repository.TurnoRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** Operaciones locales; la autorización y coordinación remota se realizan en FilaCoordinacionService. */
@Service
public class FilaVirtualService {
    private static final int MAX_INTENTOS = 3;
    private final FilaEventoRepository filas;
    private final TurnoRepository turnos;
    private final Clock clock;
    private final Validator validator;
    private final TransactionTemplate tx;
    @PersistenceContext
    private EntityManager entityManager;

    public FilaVirtualService(FilaEventoRepository filas, TurnoRepository turnos, Clock clock,
            Validator validator, PlatformTransactionManager manager) {
        this.filas = filas;
        this.turnos = turnos;
        this.clock = clock;
        this.validator = validator;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Locking reads plus READ_COMMITTED avoid a stale MySQL REPEATABLE_READ snapshot
        // when a transaction waits for another instance to release the event lock.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void configurar(String eventoId, ConfiguracionFilaRequest request) {
        validarEvento(eventoId);
        validar(request);
        ejecutarConReintento(() -> {
            FilaEvento fila = obtenerOCrear(eventoId);
            exigirNoCancelada(fila);
            fila.setCupo(request.cupo());
            fila.setDuracionMinutos(request.duracionMinutos());
            procesar(fila, ahora());
            return null;
        }, eventoId);
    }

    /** Creates only the default configuration if absent; never resets existing settings. */
    public EstadoFila prepararFila(String eventoId) {
        validarEvento(eventoId);
        return ejecutarConReintento(() -> {
            FilaEvento fila = obtenerOCrear(eventoId);
            exigirNoCancelada(fila);
            return fila.getEstado();
        }, eventoId);
    }

    /** Transición local solamente: no confirma la política de MS1. */
    public void cambiarEstadoLocal(String eventoId, EstadoFila estado) {
        validarEvento(eventoId);
        if (estado == null) throw new IllegalArgumentException("El estado es obligatorio.");
        ejecutarConReintento(() -> {
            FilaEvento fila = estado == EstadoFila.ACTIVA ? obtenerOCrear(eventoId) : bloquear(eventoId);
            exigirNoCancelada(fila);
            if (estado == EstadoFila.PAUSADA && fila.getEstado() != EstadoFila.ACTIVA
                    && fila.getEstado() != EstadoFila.PAUSADA) {
                throw new FilaConflictException("Solo se puede pausar una fila activa.");
            }
            fila.setEstado(estado);
            if (estado == EstadoFila.DESACTIVADA) invalidarActivos(fila);
            else procesar(fila, ahora());
            return null;
        }, estado == EstadoFila.ACTIVA ? eventoId : null);
    }

    public void cancelar(String eventoId) {
        validarEvento(eventoId);
        ejecutarConReintento(() -> {
            FilaEvento fila = obtenerOCrear(eventoId);
            fila.setCancelado(true);
            fila.setEstado(EstadoFila.DESACTIVADA);
            invalidarActivos(fila);
            return null;
        }, eventoId);
    }

    public ResultadoIngreso ingresar(String eventoId, String usuarioId) {
        validarEvento(eventoId);
        validarIdentificador(usuarioId, "usuarioId");
        return ejecutarConReintento(() -> {
            FilaEvento fila = bloquear(eventoId);
            exigirNoCancelada(fila);
            LocalDateTime ahora = ahora();
            procesar(fila, ahora);
            Turno existente = turnos.findActivo(fila.getId(), usuarioId).orElse(null);
            if (existente != null) return new ResultadoIngreso(false, respuesta(existente));
            if (fila.getEstado() != EstadoFila.ACTIVA) throw new FilaConflictException("La fila no permite nuevos ingresos.");
            Turno turno = new Turno();
            turno.setFila(fila);
            turno.setUsuarioId(usuarioId);
            turno.setIngresadoEn(ahora);
            turnos.saveAndFlush(turno);
            procesar(fila, ahora);
            return new ResultadoIngreso(true, respuesta(turno));
        }, null);
    }

    public TurnoResponse consultarTurno(String eventoId, String usuarioId) {
        validarEvento(eventoId);
        validarIdentificador(usuarioId, "usuarioId");
        return ejecutarConReintento(() -> {
            FilaEvento fila = bloquear(eventoId);
            procesar(fila, ahora());
            Turno turno = turnos.findActivo(fila.getId(), usuarioId)
                    .or(() -> turnos.findFirstByFilaIdAndUsuarioIdOrderByIdDesc(fila.getId(), usuarioId))
                    .orElseThrow(() -> new FilaNoEncontradaException("No existe un turno para el cliente."));
            return respuesta(turno);
        }, null);
    }

    public FilaEventoResponse consultarFila(String eventoId) {
        validarEvento(eventoId);
        return ejecutarConReintento(() -> {
            FilaEvento fila = bloquear(eventoId);
            LocalDateTime ahora = ahora();
            procesar(fila, ahora);
            return new FilaEventoResponse(eventoId, fila.getEstado(), fila.isCancelado(), fila.getCupo(),
                    fila.getDuracionMinutos(), turnos.countByFilaIdAndEstado(fila.getId(), EstadoTurno.ESPERANDO),
                    turnos.countByFilaIdAndEstadoAndExpiraEnAfter(fila.getId(), EstadoTurno.HABILITADO, ahora));
        }, null);
    }

    public ConsumoTurnoResponse consumir(String eventoId, ConsumirTurnoRequest request) {
        validarEvento(eventoId);
        validar(request);
        try {
            return ejecutarConReintento(() -> consumirEnTransaccion(eventoId, request), null);
        } catch (DataIntegrityViolationException conflicto) {
            // The competing key may have been committed on a different event lock.
            // Resolve only after rollback, in a new transaction with a current read.
            ConsumoTurnoResponse previo = tx.execute(s -> {
                Turno ganador = turnos.findByUsuarioIdAndSolicitudId(request.usuarioId(), request.solicitudId()).orElse(null);
                if (ganador == null) return null;
                FilaEvento fila = bloquearPorId(ganador.getFila().getId());
                return resolverConsumo(ganador, fila, eventoId, request, ahora());
            });
            if (previo != null) return previo;
            throw conflicto;
        }
    }

    private ConsumoTurnoResponse consumirEnTransaccion(String eventoId, ConsumirTurnoRequest request) {
        Turno previo = turnos.findByUsuarioIdAndSolicitudId(request.usuarioId(), request.solicitudId()).orElse(null);
        if (previo != null) {
            FilaEvento filaOriginal = bloquearPorId(previo.getFila().getId());
            return resolverConsumo(previo, filaOriginal, eventoId, request, ahora());
        }
        FilaEvento fila = bloquear(eventoId);
        LocalDateTime ahora = ahora();
        previo = turnos.findByUsuarioIdAndSolicitudId(request.usuarioId(), request.solicitudId()).orElse(null);
        if (previo != null) return resolverConsumo(previo, fila, eventoId, request, ahora);
        exigirNoCancelada(fila);
        if (fila.getEstado() == EstadoFila.DESACTIVADA) throw new FilaConflictException("La fila está desactivada.");
        procesar(fila, ahora);
        Turno turno = turnos.findActivo(fila.getId(), request.usuarioId())
                .orElseThrow(() -> new FilaConflictException("No existe un turno habilitado vigente."));
        if (turno.getEstado() != EstadoTurno.HABILITADO || !turno.getExpiraEn().isAfter(ahora)) {
            throw new FilaConflictException("No existe un turno habilitado vigente.");
        }
        turno.setEstado(EstadoTurno.CONSUMIDO);
        turno.setSolicitudId(request.solicitudId());
        turno.setHuellaSolicitud(request.huellaSolicitud());
        turnos.saveAndFlush(turno);
        procesar(fila, ahora);
        return resolverConsumo(turno, fila, eventoId, request, ahora);
    }

    private ConsumoTurnoResponse resolverConsumo(Turno turno, FilaEvento fila, String eventoId,
            ConsumirTurnoRequest request, LocalDateTime ahora) {
        if (!turno.getFila().getId().equals(fila.getId()) || !fila.getEventoId().equals(eventoId)
                || !turno.getHuellaSolicitud().equals(request.huellaSolicitud())) {
            throw new FilaConflictException("El identificador de solicitud corresponde a otro contenido o evento.");
        }
        boolean vigente = !fila.isCancelado() && fila.getEstado() != EstadoFila.DESACTIVADA
                && turno.getExpiraEn().isAfter(ahora);
        return new ConsumoTurnoResponse(turno.getId(), eventoId, request.solicitudId(), turno.getEstado(),
                turno.getExpiraEn().toInstant(ZoneOffset.UTC), vigente);
    }

    /** Procesa una candidata en su propia transacción y vuelve a comprobarla bajo bloqueo. */
    public void procesarFila(Long filaId) {
        if (filaId == null) throw new IllegalArgumentException("El identificador de fila es obligatorio.");
        ejecutarConReintento(() -> {
            FilaEvento fila = bloquearPorId(filaId);
            procesar(fila, ahora());
            return null;
        }, null);
    }

    private void procesar(FilaEvento fila, LocalDateTime ahora) {
        var vencidos = turnos.findByFilaIdAndEstadoAndExpiraEnLessThanEqualOrderByExpiraEnAscIdAsc(
                fila.getId(), EstadoTurno.HABILITADO, ahora);
        vencidos.forEach(t -> t.setEstado(EstadoTurno.VENCIDO));
        turnos.saveAllAndFlush(vencidos);
        if (fila.isCancelado() || fila.getEstado() != EstadoFila.ACTIVA) return;
        long habilitados = turnos.countByFilaIdAndEstadoAndExpiraEnAfter(fila.getId(), EstadoTurno.HABILITADO, ahora);
        int disponibles = (int) Math.max(0L, (long) fila.getCupo() - habilitados);
        if (disponibles == 0) return;
        var esperando = turnos.findByFilaIdAndEstadoOrderByIdAsc(fila.getId(), EstadoTurno.ESPERANDO,
                PageRequest.of(0, disponibles));
        for (Turno turno : esperando) {
            turno.setEstado(EstadoTurno.HABILITADO);
            turno.setHabilitadoEn(ahora);
            turno.setExpiraEn(ahora.plusMinutes(fila.getDuracionMinutos()));
        }
        turnos.saveAllAndFlush(esperando);
    }

    private void invalidarActivos(FilaEvento fila) {
        var activos = turnos.findByFilaIdAndEstadoInOrderByIdAsc(fila.getId(),
                List.of(EstadoTurno.ESPERANDO, EstadoTurno.HABILITADO));
        activos.forEach(t -> t.setEstado(EstadoTurno.VENCIDO));
        turnos.saveAllAndFlush(activos);
    }

    private TurnoResponse respuesta(Turno turno) {
        Long posicion = turno.getEstado() == EstadoTurno.ESPERANDO
                ? 1L + turnos.countByFilaIdAndEstadoAndIdLessThan(turno.getFila().getId(), EstadoTurno.ESPERANDO, turno.getId())
                : null;
        return new TurnoResponse(turno.getId(), turno.getFila().getEventoId(), turno.getEstado(), posicion,
                instante(turno.getIngresadoEn()), instante(turno.getHabilitadoEn()), instante(turno.getExpiraEn()));
    }

    private FilaEvento bloquear(String eventoId) {
        FilaEvento fila = filas.findByEventoIdForUpdate(eventoId)
                .orElseThrow(() -> new FilaNoEncontradaException("No existe la fila del evento."));
        return refrescarBloqueada(fila);
    }

    private FilaEvento bloquearPorId(Long filaId) {
        FilaEvento fila = filas.findByIdForUpdate(filaId)
                .orElseThrow(() -> new FilaNoEncontradaException("No existe la fila."));
        return refrescarBloqueada(fila);
    }

    private FilaEvento refrescarBloqueada(FilaEvento fila) {
        // A request-scoped persistence context can retain an older entity between
        // local transactions; refresh after locking to observe a competing commit.
        entityManager.refresh(fila, LockModeType.PESSIMISTIC_WRITE);
        return fila;
    }

    private FilaEvento obtenerOCrear(String eventoId) {
        FilaEvento existente = filas.findByEventoIdForUpdate(eventoId).orElse(null);
        if (existente != null) return refrescarBloqueada(existente);
        FilaEvento nueva = new FilaEvento();
        nueva.setEventoId(eventoId);
        return filas.saveAndFlush(nueva);
    }

    private <T> T ejecutarConReintento(Supplier<T> operacion, String eventoCreadoPorCompetidor) {
        for (int intento = 1; intento <= MAX_INTENTOS; intento++) {
            try {
                return tx.execute(s -> operacion.get());
            } catch (CannotAcquireLockException e) {
                if (intento == MAX_INTENTOS) throw e;
            } catch (DataIntegrityViolationException e) {
                if (eventoCreadoPorCompetidor == null || intento == MAX_INTENTOS
                        || !Boolean.TRUE.equals(tx.execute(s -> filas.findByEventoId(eventoCreadoPorCompetidor).isPresent()))) throw e;
            }
        }
        throw new IllegalStateException("No se pudo completar la transacción.");
    }

    private void exigirNoCancelada(FilaEvento fila) {
        if (fila.isCancelado()) throw new FilaConflictException("El evento está cancelado.");
    }

    private void validarEvento(String valor) {
        if (valor == null || valor.isBlank() || valor.length() > 255) throw new IllegalArgumentException("eventoId inválido.");
    }

    private void validarIdentificador(String valor, String nombre) {
        if (valor == null || valor.isEmpty() || valor.codePointCount(0, valor.length()) > 255)
            throw new IllegalArgumentException(nombre + " inválido.");
    }

    private <T> void validar(T valor) {
        if (valor == null) throw new IllegalArgumentException("El cuerpo es obligatorio.");
        var violaciones = validator.validate(valor);
        if (!violaciones.isEmpty()) throw new ConstraintViolationException(violaciones);
    }

    private LocalDateTime ahora() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC); }
    private Instant instante(LocalDateTime fecha) { return fecha == null ? null : fecha.toInstant(ZoneOffset.UTC); }
}
