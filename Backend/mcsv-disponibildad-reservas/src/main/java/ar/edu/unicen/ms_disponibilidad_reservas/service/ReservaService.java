package ar.edu.unicen.ms_disponibilidad_reservas.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleReservaConsultaResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleReservaSolicitud;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.ReservaConsultaResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.ReservaCreadaResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.DetalleReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.ReservaRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

/** Crea reservas y aplica el inventario y el límite cliente/evento de forma atómica. */
@Service
public class ReservaService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservaService.class);

    private static final String HUELLA_VERSION = "v1:";
    private static final Duration DURACION_RESERVA = Duration.ofMinutes(10);
    private static final int MAX_INTENTOS_TRANSACCION_CREACION = 3;

    private final EventoDisponibilidadRepository eventosRepository;
    private final SectorDisponibilidadRepository sectoresRepository;
    private final ReservaRepository reservasRepository;
    private final CantidadClienteEventoRepository cantidadesRepository;
    private final CantidadClienteEventoService cantidadesService;
    private final DisponibilidadService disponibilidadService;
    private final DisponibilidadAgotamientoService agotamientoService;
    private final Validator validator;
    private final Clock clock;
    private final TransactionTemplate transaccion;
    private final TransactionTemplate transaccionConfirmacion;

    @PersistenceContext
    private EntityManager entityManager;

    public ReservaService(
            EventoDisponibilidadRepository eventosRepository,
            SectorDisponibilidadRepository sectoresRepository,
            ReservaRepository reservasRepository,
            CantidadClienteEventoRepository cantidadesRepository,
            CantidadClienteEventoService cantidadesService,
            DisponibilidadService disponibilidadService,
            DisponibilidadAgotamientoService agotamientoService,
            Validator validator,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.eventosRepository = eventosRepository;
        this.sectoresRepository = sectoresRepository;
        this.reservasRepository = reservasRepository;
        this.cantidadesRepository = cantidadesRepository;
        this.cantidadesService = cantidadesService;
        this.disponibilidadService = disponibilidadService;
        this.agotamientoService = agotamientoService;
        this.validator = validator;
        this.clock = clock;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaccionConfirmacion = new TransactionTemplate(transactionManager);
    }

    public ResultadoCreacionReserva crear(String eventoId, String usuarioId, CrearReservaRequest request) {
        validarEntrada(eventoId, usuarioId, request);
        String huella = calcularHuella(eventoId, request);
        for (int intento = 1; intento <= MAX_INTENTOS_TRANSACCION_CREACION; intento++) {
            try {
                return transaccion.execute(
                        status -> crearEnTransaccion(eventoId, usuarioId, request, huella));
            } catch (DataIntegrityViolationException violacionPersistencia) {
                // Una carrera por la clave idempotente puede resolverse como violación de
                // unicidad. Buscar al ganador solo después de que TransactionTemplate hizo
                // rollback evita continuar dentro de una transacción marcada rollback-only.
                Reserva existente = buscarReservaIdempotenteTrasRollback(usuarioId, request.solicitudId());
                if (existente != null) {
                    return resolverReintento(existente, eventoId, huella, clock.instant());
                }
                throw violacionPersistencia;
            } catch (CannotAcquireLockException conflictoBloqueo) {
                // InnoDB también puede abortar una creación válida por deadlock de gap locks
                // al insertar agregados distintos. Si no ganó la clave idempotente, repetir
                // la unidad completa en una transacción nueva; no hubo efectos parciales.
                Reserva existente = buscarReservaIdempotenteTrasRollback(usuarioId, request.solicitudId());
                if (existente != null) {
                    return resolverReintento(existente, eventoId, huella, clock.instant());
                }
                if (intento == MAX_INTENTOS_TRANSACCION_CREACION) {
                    throw conflictoBloqueo;
                }
            }
        }
        throw new IllegalStateException("No se pudo completar la transacción de creación de reserva.");
    }

    private Reserva buscarReservaIdempotenteTrasRollback(String usuarioId, String solicitudId) {
        return transaccion.execute(status -> reservasRepository
                .findByUsuarioIdAndClaveIdempotenciaForUpdate(usuarioId, solicitudId).orElse(null));
    }

    @Transactional(readOnly = true)
    public ReservaConsultaResponse consultar(String reservaId) {
        Reserva reserva = reservasRepository.findById(reservaId)
                .orElseThrow(ReservaNoEncontradaException::new);
        List<DetalleReservaConsultaResponse> detalles = reserva.getDetalles().stream()
                .map(detalle -> detalle.getFila() != null
                        ? new DetalleReservaConsultaResponse(
                                detalle.getSectorId(), detalle.getFila(), detalle.getNumero(), null)
                        : new DetalleReservaConsultaResponse(
                                detalle.getSectorId(), null, null, detalle.getCantidad()))
                .toList();
        return new ReservaConsultaResponse(
                reserva.getId(),
                reserva.getUsuarioId(),
                reserva.getEventoId(),
                reserva.getEstado(),
                instanteUtc(reserva.getExpiraEn()),
                detalles);
    }

    public void confirmar(String reservaId) {
        boolean transaccionExterna = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            transaccionConfirmacion.execute(status -> {
                confirmarEnTransaccion(reservaId);
                return null;
            });
        } catch (ReservaConflictException conflicto) {
            if (transaccionExterna) {
                throw conflicto;
            }
            // A transaction that waited on a competing transition can observe a stale
            // persistence-context snapshot in some engines. After rollback, resolve the
            // idempotent case in a fresh transaction; preserve genuine conflicts.
            Boolean yaConfirmada = transaccion.execute(status -> reservasRepository.findById(reservaId)
                    .map(reserva -> reserva.getEstado() == EstadoReserva.CONFIRMADA)
                    .orElse(false));
            if (!Boolean.TRUE.equals(yaConfirmada)) {
                throw conflicto;
            }
        }
    }

    private void confirmarEnTransaccion(String reservaId) {
        String eventoId = reservasRepository.findEventoIdById(reservaId)
                .orElseThrow(ReservaNoEncontradaException::new);
        eventosRepository.findByEventoIdForUpdate(eventoId)
                .orElseThrow(() -> new IllegalStateException("La reserva no tiene evento de disponibilidad local."));
        Reserva reserva = reservasRepository.findByIdForUpdate(reservaId)
                .orElseThrow(ReservaNoEncontradaException::new);
        // The transaction may have started before waiting for the event lock. Refresh
        // the managed reservation under its lock so a concurrent transition is visible.
        entityManager.refresh(reserva, LockModeType.PESSIMISTIC_WRITE);

        if (reserva.getEstado() == EstadoReserva.CONFIRMADA) {
            return;
        }
        if (reserva.getEstado() == EstadoReserva.LIBERADA) {
            throw new ReservaLiberadaException();
        }

        Instant ahora = clock.instant();
        if (!instanteUtc(reserva.getExpiraEn()).isAfter(ahora)) {
            throw new ReservaVencidaException();
        }

        boolean agotadoAntes = agotamientoService.estaAgotado(reserva.getEventoId());

        long totalUnidades = reserva.getDetalles().stream()
                .mapToLong(DetalleReserva::getCantidad)
                .reduce(0L, Math::addExact);
        int cantidad = Math.toIntExact(totalUnidades);
        CantidadClienteEvento agregado = cantidadesRepository
                .findByUsuarioIdAndEventoIdForUpdate(reserva.getUsuarioId(), reserva.getEventoId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el agregado de cantidades de la reserva."));
        if (agregado.getCantidadReservada() < cantidad) {
            throw new ReservaConflictException("Las cantidades reservadas no coinciden con la reserva.");
        }

        disponibilidadService.confirmar(reserva.getDetalles());

        agregado.setCantidadReservada(agregado.getCantidadReservada() - cantidad);
        agregado.setCantidadConfirmada(Math.addExact(agregado.getCantidadConfirmada(), cantidad));
        cantidadesRepository.saveAndFlush(agregado);

        reserva.setEstado(EstadoReserva.CONFIRMADA);
        reservasRepository.saveAndFlush(reserva);
        agotamientoService.registrarTransicionSiCambio(reserva.getEventoId(), agotadoAntes);
    }

    /** Releases a complete temporary reservation after payment rejection, exactly once. */
    @Transactional(rollbackFor = Exception.class)
    public void liberarPorPagoRechazado(String reservaId) {
        Reserva reserva = reservasRepository.findByIdForUpdate(reservaId)
                .orElseThrow(ReservaNoEncontradaException::new);

        if (reserva.getEstado() == EstadoReserva.LIBERADA) {
            return;
        }
        if (reserva.getEstado() == EstadoReserva.CONFIRMADA) {
            throw new ReservaConflictException(
                    "No se puede liberar por pago rechazado una reserva ya confirmada.");
        }

        liberarReservaVigente(reserva);
    }

    /**
     * Releases every still-vigente reservation for an event. When invoked by
     * EstadoVentaService this joins the event-state transaction, so an error
     * rolls back the event transition and every inventory/count update together.
     */
    @Transactional(rollbackFor = Exception.class)
    public int liberarReservasVigentesDelEvento(String eventoId) {
        int liberadas = 0;
        List<String> candidatas = reservasRepository.findIdsByEventoIdAndEstado(eventoId, EstadoReserva.VIGENTE);
        for (String reservaId : candidatas) {
            Reserva reserva = reservasRepository.findByIdForUpdate(reservaId).orElse(null);
            if (reserva == null || !eventoId.equals(reserva.getEventoId())
                    || reserva.getEstado() != EstadoReserva.VIGENTE) {
                continue;
            }
            liberarReservaVigente(reserva);
            liberadas++;
        }
        return liberadas;
    }

    /**
     * Processes each due reservation in its own transaction. Locking and rechecking each
     * candidate makes this safe against concurrent confirmation or manual release, while
     * a failure for one reservation does not abort the rest of the batch.
     */
    public int expirarReservasVencidas() {
        LocalDateTime limite = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> candidatas = reservasRepository.findIdsVigentesVencidasHasta(
                EstadoReserva.VIGENTE, limite);
        int expiradas = 0;
        for (String reservaId : candidatas) {
            try {
                Boolean procesada = transaccion.execute(status -> expirarSiCorresponde(reservaId));
                if (Boolean.TRUE.equals(procesada)) {
                    expiradas++;
                }
            } catch (RuntimeException exception) {
                LOGGER.error("No se pudo expirar la reserva {} ({}); se continuará con las demás.",
                        reservaId, exception.getClass().getSimpleName());
            }
        }
        return expiradas;
    }

    private boolean expirarSiCorresponde(String reservaId) {
        Reserva reserva = reservasRepository.findByIdForUpdate(reservaId).orElse(null);
        if (reserva == null || reserva.getEstado() != EstadoReserva.VIGENTE) {
            return false;
        }
        if (instanteUtc(reserva.getExpiraEn()).isAfter(clock.instant())) {
            return false;
        }
        liberarReservaVigente(reserva);
        return true;
    }

    private void liberarReservaVigente(Reserva reserva) {

        long unidades = reserva.getDetalles().stream()
                .mapToLong(DetalleReserva::getCantidad)
                .reduce(0L, Math::addExact);
        int cantidad = Math.toIntExact(unidades);
        CantidadClienteEvento agregado = cantidadesRepository
                .findByUsuarioIdAndEventoIdForUpdate(reserva.getUsuarioId(), reserva.getEventoId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el agregado de cantidades de la reserva."));
        if (agregado.getCantidadReservada() < cantidad) {
            throw new IllegalStateException("Las cantidades reservadas no coinciden con la reserva.");
        }

        // All updates join this transaction. The locked reservation serializes retries.
        disponibilidadService.liberar(reserva.getDetalles());
        agregado.setCantidadReservada(agregado.getCantidadReservada() - cantidad);
        cantidadesRepository.saveAndFlush(agregado);
        reserva.setEstado(EstadoReserva.LIBERADA);
        reservasRepository.saveAndFlush(reserva);
    }

    private ResultadoCreacionReserva crearEnTransaccion(
            String eventoId, String usuarioId, CrearReservaRequest request, String huella) {
        EventoDisponibilidad evento = eventosRepository.findByEventoIdForUpdate(eventoId).orElse(null);

        Reserva existente = reservasRepository
                .findByUsuarioIdAndClaveIdempotencia(usuarioId, request.solicitudId()).orElse(null);
        if (existente != null) {
            return resolverReintento(existente, eventoId, huella, clock.instant());
        }
        if (evento == null) {
            throw new DisponibilidadNoRegistradaException(
                    "No hay disponibilidad registrada en MS2 para el evento solicitado");
        }

        validarEventoReservable(evento);
        List<DetalleReserva> detalles = construirDetalles(eventoId, request);
        long unidadesSolicitadas = request.detalles().stream().mapToLong(this::cantidadDelDetalle).sum();

        Instant ahora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiraEn = ahora.plus(DURACION_RESERVA);
        Reserva reserva = new Reserva();
        reserva.setUsuarioId(usuarioId);
        reserva.setEventoId(eventoId);
        reserva.setEstado(EstadoReserva.VIGENTE);
        // DATETIME(6) no conserva zona: persistimos el reloj UTC y lo leemos con la misma semántica.
        reserva.setExpiraEn(LocalDateTime.ofInstant(expiraEn, ZoneOffset.UTC));
        reserva.setClaveIdempotencia(request.solicitudId());
        reserva.setHuellaSolicitud(huella);
        detalles.forEach(reserva::agregarDetalle);

        // El INSERT/flush hace que la clave única arbitre las carreras antes de tocar inventario.
        reserva = reservasRepository.saveAndFlush(reserva);

        CantidadClienteEvento cantidad = cantidadesService.obtenerOCrear(usuarioId, eventoId);
        int limite = evento.getLimiteEntradasPorCliente();
        long totalClienteEvento = (long) cantidad.getCantidadReservada() + cantidad.getCantidadConfirmada()
                + unidadesSolicitadas;
        if (totalClienteEvento > limite) {
            throw new ReservaConflictException("La reserva supera el límite de entradas por cliente para el evento.");
        }

        try {
            disponibilidadService.reservar(detalles);
        } catch (DisponibilidadException conflictoInventario) {
            throw new ReservaConflictException(
                    "Uno o más lugares solicitados ya no están disponibles para reservar.");
        }

        cantidad.setCantidadReservada(Math.addExact(cantidad.getCantidadReservada(), Math.toIntExact(unidadesSolicitadas)));
        cantidadesRepository.saveAndFlush(cantidad);
        return new ResultadoCreacionReserva(HttpStatus.CREATED, respuesta(reserva));
    }

    private List<DetalleReserva> construirDetalles(String eventoId, CrearReservaRequest request) {
        List<DetalleReserva> resultado = new ArrayList<>(request.detalles().size());
        Set<ClaveAsiento> asientosUnicos = new HashSet<>();
        for (DetalleReservaSolicitud solicitado : request.detalles()) {
            boolean numerado = solicitado.fila() != null && solicitado.numero() != null
                    && solicitado.cantidad() == null;
            boolean noNumerado = solicitado.fila() == null && solicitado.numero() == null
                    && solicitado.cantidad() != null;
            if (!numerado && !noNumerado) {
                throw new IllegalArgumentException(
                        "Cada detalle debe representar un asiento numerado o una cantidad no numerada, sin mezclar campos.");
            }

            SectorDisponibilidad sector = sectoresRepository.findByEventoIdAndSectorId(eventoId, solicitado.sectorId())
                    .orElseThrow(() -> new ReservaConflictException(
                            "El sector solicitado no pertenece a la disponibilidad del evento."));
            TipoSector tipoSolicitado = numerado ? TipoSector.NUMERADO : TipoSector.NO_NUMERADO;
            if (sector.getTipo() != tipoSolicitado) {
                throw new ReservaConflictException("El detalle no corresponde al tipo de sector solicitado.");
            }
            if (numerado && !asientosUnicos.add(new ClaveAsiento(solicitado.sectorId(), solicitado.fila(), solicitado.numero()))) {
                throw new IllegalArgumentException("La solicitud repite un asiento numerado.");
            }

            DetalleReserva detalle = new DetalleReserva();
            detalle.setSectorId(solicitado.sectorId());
            detalle.setFila(solicitado.fila());
            detalle.setNumero(solicitado.numero());
            detalle.setCantidad(numerado ? 1 : solicitado.cantidad());
            resultado.add(detalle);
        }
        return resultado;
    }

    private void validarEventoReservable(EventoDisponibilidad evento) {
        if (evento.getEstadoEvento() != EstadoEvento.PUBLICADO || evento.isVentaSuspendida()) {
            throw new ReservaConflictException("El evento no permite nuevas reservas en su estado actual.");
        }
    }

    private void validarEntrada(String eventoId, String usuarioId, CrearReservaRequest request) {
        if (eventoId == null || eventoId.isBlank() || eventoId.length() > 255) {
            throw new IllegalArgumentException("eventoId es obligatorio y debe tener como máximo 255 caracteres.");
        }
        if (usuarioId == null || usuarioId.isEmpty() || longitudCaracteres(usuarioId) > 255) {
            throw new IllegalArgumentException("X-User-Id es obligatorio y debe tener entre 1 y 255 caracteres.");
        }
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo de la solicitud es obligatorio.");
        }
        Set<ConstraintViolation<CrearReservaRequest>> violaciones = validator.validate(request);
        if (!violaciones.isEmpty()) {
            throw new ConstraintViolationException(violaciones);
        }
        if (longitudCaracteres(request.solicitudId()) > 255) {
            throw new IllegalArgumentException("solicitudId no puede superar 255 caracteres.");
        }
    }

    private long cantidadDelDetalle(DetalleReservaSolicitud detalle) {
        return detalle.fila() != null ? 1L : detalle.cantidad();
    }

    private ResultadoCreacionReserva resolverReintento(
            Reserva existente, String eventoId, String huella, Instant ahora) {
        if (!existente.getEventoId().equals(eventoId) || !existente.getHuellaSolicitud().equals(huella)) {
            throw new ReservaConflictException(
                    "El identificador de solicitud ya está asociado a otro contenido para este cliente.");
        }
        if (existente.getEstado() != EstadoReserva.VIGENTE
                || !instanteUtc(existente.getExpiraEn()).isAfter(ahora)) {
            throw new ReservaConflictException(
                    "La reserva original asociada a esta solicitud ya no está vigente.");
        }
        return new ResultadoCreacionReserva(HttpStatus.OK, respuesta(existente));
    }

    private ReservaCreadaResponse respuesta(Reserva reserva) {
        return new ReservaCreadaResponse(reserva.getId(), reserva.getEstado(), instanteUtc(reserva.getExpiraEn()));
    }

    private Instant instanteUtc(LocalDateTime fecha) {
        return fecha.toInstant(ZoneOffset.UTC);
    }

    private int longitudCaracteres(String valor) {
        return valor.codePointCount(0, valor.length());
    }

    private String calcularHuella(String eventoId, CrearReservaRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream salida = new DataOutputStream(bytes)) {
                salida.write("MS2-RESERVA-v1".getBytes(StandardCharsets.UTF_8));
                salida.writeUTF(eventoId);
                salida.writeUTF(request.solicitudId());
                salida.writeInt(request.detalles().size());
                for (DetalleReservaSolicitud detalle : request.detalles()) {
                    salida.writeUTF(detalle.sectorId());
                    if (detalle.fila() != null && detalle.numero() != null && detalle.cantidad() == null) {
                        salida.writeByte(1);
                        salida.writeInt(detalle.fila());
                        salida.writeInt(detalle.numero());
                    } else if (detalle.fila() == null && detalle.numero() == null && detalle.cantidad() != null) {
                        salida.writeByte(2);
                        salida.writeInt(detalle.cantidad());
                    } else {
                        throw new IllegalArgumentException("La forma del detalle de reserva no es válida.");
                    }
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return HUELLA_VERSION + HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo calcular la huella de la solicitud de reserva.", exception);
        }
    }

    private record ClaveAsiento(String sectorId, Integer fila, Integer numero) {
    }
}
