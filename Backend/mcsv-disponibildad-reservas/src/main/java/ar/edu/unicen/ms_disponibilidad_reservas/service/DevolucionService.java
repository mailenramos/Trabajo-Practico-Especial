package ar.edu.unicen.ms_disponibilidad_reservas.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleDevolucionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.DetalleReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.DevolucionRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.DevolucionRepository.RegistroDevolucion;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.ReservaRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;

/** Applies approved partial refunds atomically and recognizes their global idempotency key. */
@Service
public class DevolucionService {

    private static final String MOTIVO = "DEVOLUCION_APROBADA";
    private static final String HUELLA_VERSION = "MS2-DEVOLUCION-v1";
    private static final int MAX_INTENTOS_TRANSACCION = 3;
    private static final Comparator<DetalleDevolucionRequest> ORDEN_DETALLES =
            Comparator.comparing(DetalleDevolucionRequest::sectorId)
                    .thenComparing(d -> d.fila() == null ? 1 : 0)
                    .thenComparing(DetalleDevolucionRequest::fila, Comparator.nullsFirst(Integer::compareTo))
                    .thenComparing(DetalleDevolucionRequest::numero, Comparator.nullsFirst(Integer::compareTo))
                    .thenComparing(DetalleDevolucionRequest::cantidad, Comparator.nullsFirst(Integer::compareTo));

    private final ReservaRepository reservasRepository;
    private final CantidadClienteEventoRepository cantidadesRepository;
    private final SectorDisponibilidadRepository sectoresRepository;
    private final AsientoDisponibilidadRepository asientosRepository;
    private final DevolucionRepository devolucionesRepository;
    private final DisponibilidadService disponibilidadService;
    private final EventoDisponibilidadRepository eventosRepository;
    private final DisponibilidadAgotamientoService agotamientoService;
    private final TransactionTemplate transaccion;

    @PersistenceContext
    private EntityManager entityManager;

    public DevolucionService(
            ReservaRepository reservasRepository,
            CantidadClienteEventoRepository cantidadesRepository,
            SectorDisponibilidadRepository sectoresRepository,
            AsientoDisponibilidadRepository asientosRepository,
            DevolucionRepository devolucionesRepository,
            DisponibilidadService disponibilidadService,
            EventoDisponibilidadRepository eventosRepository,
            DisponibilidadAgotamientoService agotamientoService,
            PlatformTransactionManager transactionManager) {
        this.reservasRepository = reservasRepository;
        this.cantidadesRepository = cantidadesRepository;
        this.sectoresRepository = sectoresRepository;
        this.asientosRepository = asientosRepository;
        this.devolucionesRepository = devolucionesRepository;
        this.disponibilidadService = disponibilidadService;
        this.eventosRepository = eventosRepository;
        this.agotamientoService = agotamientoService;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void devolverAprobada(String reservaId, LiberarReservaRequest request) {
        SolicitudNormalizada solicitud = normalizar(reservaId, request);

        // Fast path for a completed operation (also detects a UUID reused on another route).
        RegistroDevolucion existente = transaccion.execute(
                status -> devolucionesRepository.findById(solicitud.devolucionId()).orElse(null));
        if (existente != null) {
            comprobarReintento(existente, solicitud);
            return;
        }

        for (int intento = 1; intento <= MAX_INTENTOS_TRANSACCION; intento++) {
            try {
                transaccion.execute(status -> {
                    procesarEnTransaccion(solicitud);
                    return null;
                });
                return;
            } catch (DataIntegrityViolationException conflictoIntegridad) {
                RegistroDevolucion ganadora = buscarTrasRollback(solicitud.devolucionId());
                if (ganadora != null) {
                    comprobarReintento(ganadora, solicitud);
                    return;
                }
                if (asientoYaDevueltoTrasRollback(solicitud)) {
                    throw new ReservaConflictException("El asiento solicitado ya fue devuelto.");
                }
                throw conflictoIntegridad;
            } catch (PessimisticLockingFailureException deadlock) {
                RegistroDevolucion ganadora = buscarTrasRollback(solicitud.devolucionId());
                if (ganadora != null) {
                    comprobarReintento(ganadora, solicitud);
                    return;
                }
                if (intento == MAX_INTENTOS_TRANSACCION) {
                    throw deadlock;
                }
            }
        }
    }

    private void procesarEnTransaccion(SolicitudNormalizada solicitud) {
        // Never retain a global ledger lock while waiting for an event lock.
        String eventoId = reservasRepository.findEventoIdById(solicitud.reservaId())
                .orElseThrow(ReservaNoEncontradaException::new);
        eventosRepository.findByEventoIdForUpdate(eventoId)
                .orElseThrow(() -> new IllegalStateException("La reserva no tiene evento de disponibilidad local."));
        Reserva reserva = reservasRepository.findByIdForUpdate(solicitud.reservaId())
                .orElseThrow(ReservaNoEncontradaException::new);
        entityManager.detach(reserva);
        reserva = reservasRepository.findByIdForUpdate(solicitud.reservaId())
                .orElseThrow(ReservaNoEncontradaException::new);

        // A same-reservation retry can have waited on this row lock. Re-read with a current locking read.
        RegistroDevolucion existente = devolucionesRepository.findByIdForUpdate(solicitud.devolucionId()).orElse(null);
        if (existente != null) {
            comprobarReintento(existente, solicitud);
            return;
        }
        if (reserva.getEstado() != EstadoReserva.CONFIRMADA) {
            throw new ReservaConflictException("Solo se pueden devolver detalles de una reserva confirmada.");
        }

        boolean agotadoAntes = agotamientoService.estaAgotado(reserva.getEventoId());

        List<DetalleAplicado> aplicados = validarYAsignarDetalles(reserva, solicitud.detalles());
        int total = aplicados.stream().mapToInt(DetalleAplicado::cantidad).reduce(0, Math::addExact);
        if (total <= 0) {
            throw new IllegalArgumentException("La devolución debe incluir una cantidad positiva de entradas.");
        }

        CantidadClienteEvento agregado = cantidadesRepository
                .findByUsuarioIdAndEventoIdForUpdate(reserva.getUsuarioId(), reserva.getEventoId())
                .orElseThrow(() -> new IllegalStateException("No existe el agregado de cantidades de la reserva."));
        if (agregado.getCantidadConfirmada() < total) {
            throw new ReservaConflictException("Las cantidades confirmadas no alcanzan para esta devolución.");
        }

        // Register the key and exact source details in the same transaction as inventory and counters.
        devolucionesRepository.insert(solicitud.devolucionId(), reserva.getId(), solicitud.huella());
        List<DetalleReserva> inventarioADevolver = new ArrayList<>();
        for (DetalleAplicado aplicado : aplicados) {
            DetalleReserva original = aplicado.original();
            if (original.getFila() != null) {
                devolucionesRepository.insertReturnedSeat(
                        solicitud.devolucionId(), reserva.getId(), original.getId());
                inventarioADevolver.add(original);
            } else {
                devolucionesRepository.insertReturnedQuantity(
                        solicitud.devolucionId(), reserva.getId(), original.getId(), aplicado.cantidad());
                DetalleReserva parcial = new DetalleReserva();
                parcial.setReserva(reserva);
                parcial.setSectorId(original.getSectorId());
                parcial.setCantidad(aplicado.cantidad());
                inventarioADevolver.add(parcial);
            }
        }

        try {
            disponibilidadService.devolver(inventarioADevolver);
        } catch (DisponibilidadException noVendido) {
            throw new ReservaConflictException("Uno o más lugares ya no siguen vendidos.");
        }

        agregado.setCantidadConfirmada(agregado.getCantidadConfirmada() - total);
        cantidadesRepository.saveAndFlush(agregado);
        agotamientoService.registrarTransicionSiCambio(reserva.getEventoId(), agotadoAntes);
    }

    private List<DetalleAplicado> validarYAsignarDetalles(
            Reserva reserva, List<DetalleDevolucionRequest> solicitudes) {
        List<DetalleReserva> originales = reserva.getDetalles().stream()
                .sorted(Comparator.comparing(DetalleReserva::getId))
                .toList();
        List<DetalleAplicado> resultado = new ArrayList<>();
        Map<String, Integer> cantidadesPorSector = new TreeMap<>();

        for (DetalleDevolucionRequest detalle : solicitudes) {
            if (detalle.fila() != null) {
                DetalleReserva original = originales.stream()
                        .filter(d -> detalle.sectorId().equals(d.getSectorId())
                                && detalle.fila().equals(d.getFila())
                                && detalle.numero().equals(d.getNumero()))
                        .findFirst()
                        .orElseThrow(() -> new ReservaConflictException(
                                "El asiento solicitado no pertenece a la reserva indicada."));
                SectorDisponibilidad sector = obtenerSector(reserva, detalle.sectorId());
                if (sector.getTipo() != TipoSector.NUMERADO
                        || original.getCantidad() != 1
                        || !asientosRepository.existsByEventoIdAndSectorIdAndFilaAndNumeroAndEstado(
                                reserva.getEventoId(), detalle.sectorId(), detalle.fila(), detalle.numero(),
                                EstadoAsiento.VENDIDO)) {
                    throw new ReservaConflictException("El asiento solicitado no sigue vendido.");
                }
                if (devolucionesRepository.seatWasReturned(original.getId())) {
                    throw new ReservaConflictException("El asiento solicitado ya fue devuelto.");
                }
                resultado.add(new DetalleAplicado(original, 1));
            } else {
                List<DetalleReserva> sectorOriginales = originales.stream()
                        .filter(d -> detalle.sectorId().equals(d.getSectorId())
                                && d.getFila() == null && d.getNumero() == null)
                        .toList();
                if (sectorOriginales.isEmpty()) {
                    throw new ReservaConflictException(
                            "La cantidad solicitada no pertenece a un sector no numerado de la reserva.");
                }
                SectorDisponibilidad sector = obtenerSector(reserva, detalle.sectorId());
                if (sector.getTipo() != TipoSector.NO_NUMERADO) {
                    throw new ReservaConflictException("El detalle no corresponde a un sector no numerado.");
                }

                int pendienteTotal = 0;
                Map<Long, Integer> pendientesPorDetalle = new TreeMap<>();
                for (DetalleReserva original : sectorOriginales) {
                    int devuelto = devolucionesRepository.returnedQuantity(original.getId());
                    if (devuelto > original.getCantidad()) {
                        throw new IllegalStateException("El historial de devoluciones supera el detalle original.");
                    }
                    int pendiente = original.getCantidad() - devuelto;
                    pendientesPorDetalle.put(original.getId(), pendiente);
                    pendienteTotal = Math.addExact(pendienteTotal, pendiente);
                }
                if (detalle.cantidad() > pendienteTotal) {
                    throw new ReservaConflictException(
                            "La cantidad solicitada supera la cantidad vendida pendiente de devolución.");
                }
                cantidadesPorSector.merge(detalle.sectorId(), detalle.cantidad(), Math::addExact);

                // Non-numbered units are indistinguishable. Allocate them deterministically to original
                // reservation details in ID order so the cumulative history remains auditable.
                int aAsignar = detalle.cantidad();
                for (DetalleReserva original : sectorOriginales) {
                    int asignada = Math.min(aAsignar, pendientesPorDetalle.get(original.getId()));
                    if (asignada > 0) {
                        resultado.add(new DetalleAplicado(original, asignada));
                        aAsignar -= asignada;
                    }
                    if (aAsignar == 0) break;
                }
            }
        }

        for (Map.Entry<String, Integer> entrada : cantidadesPorSector.entrySet()) {
            SectorDisponibilidad sector = obtenerSector(reserva, entrada.getKey());
            if (sector.getCantidadVendida() < entrada.getValue()) {
                throw new ReservaConflictException("El sector ya no tiene vendida esa cantidad.");
            }
        }
        return resultado;
    }

    private SectorDisponibilidad obtenerSector(Reserva reserva, String sectorId) {
        return sectoresRepository.findByEventoIdAndSectorId(reserva.getEventoId(), sectorId)
                .orElseThrow(() -> new ReservaConflictException("El sector no pertenece a la reserva indicada."));
    }

    private SolicitudNormalizada normalizar(String reservaId, LiberarReservaRequest request) {
        ValidacionUuid.exigir(reservaId, "reservaId");
        if (request == null || !MOTIVO.equals(request.motivo())) {
            throw new IllegalArgumentException("El motivo debe ser DEVOLUCION_APROBADA.");
        }
        if (request.devolucionId() == null) {
            throw new IllegalArgumentException("devolucionId es obligatorio.");
        }
        ValidacionUuid.exigir(request.devolucionId(), "devolucionId");
        UUID uuid;
        try {
            uuid = UUID.fromString(request.devolucionId());
        } catch (IllegalArgumentException invalidUuid) {
            throw new IllegalArgumentException("devolucionId debe ser un UUID válido.");
        }
        String canonico = uuid.toString();
        if (!canonico.equalsIgnoreCase(request.devolucionId())) {
            throw new IllegalArgumentException("devolucionId debe usar el formato canónico UUID.");
        }
        if (request.detalles() == null || request.detalles().isEmpty()) {
            throw new IllegalArgumentException("detalles debe contener al menos un lugar o cantidad.");
        }

        Set<DetalleDevolucionRequest> unicos = new HashSet<>();
        Set<String> sectoresNoNumerados = new HashSet<>();
        for (DetalleDevolucionRequest detalle : request.detalles()) {
            if (detalle == null || detalle.sectorId() == null || detalle.sectorId().isBlank()) {
                throw new IllegalArgumentException("Cada detalle debe incluir sectorId.");
            }
            boolean numerado = detalle.fila() != null && detalle.numero() != null && detalle.cantidad() == null;
            boolean noNumerado = detalle.fila() == null && detalle.numero() == null
                    && detalle.cantidad() != null && detalle.cantidad() > 0;
            if (!numerado && !noNumerado) {
                throw new IllegalArgumentException("Cada detalle debe ser un asiento o una cantidad, sin mezclar campos.");
            }
            if (numerado && detalle.numero() <= 0) {
                throw new IllegalArgumentException("numero debe ser positivo.");
            }
            if (!unicos.add(detalle)) {
                throw new IllegalArgumentException("La solicitud contiene detalles duplicados.");
            }
            if (noNumerado && !sectoresNoNumerados.add(detalle.sectorId())) {
                throw new IllegalArgumentException("No se permite repetir un sector no numerado en detalles.");
            }
        }

        List<DetalleDevolucionRequest> ordenados = request.detalles().stream().sorted(ORDEN_DETALLES).toList();
        return new SolicitudNormalizada(UUID.fromString(reservaId).toString(), canonico, ordenados, huella(ordenados));
    }

    private String huella(List<DetalleDevolucionRequest> detalles) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream salida = new DataOutputStream(bytes)) {
                salida.write(HUELLA_VERSION_BYTES);
                salida.writeInt(detalles.size());
                for (DetalleDevolucionRequest detalle : detalles) {
                    salida.writeUTF(detalle.sectorId());
                    if (detalle.fila() != null) {
                        salida.writeByte(1);
                        salida.writeInt(detalle.fila());
                        salida.writeInt(detalle.numero());
                    } else {
                        salida.writeByte(2);
                        salida.writeInt(detalle.cantidad());
                    }
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo calcular la huella de la devolución.", exception);
        }
    }

    private static final byte[] HUELLA_VERSION_BYTES = HUELLA_VERSION.getBytes(StandardCharsets.UTF_8);

    private void comprobarReintento(RegistroDevolucion existente, SolicitudNormalizada solicitud) {
        if (!existente.reservaId().equals(solicitud.reservaId())
                || !existente.huella().equals(solicitud.huella())) {
            throw new ReservaConflictException(
                    "El devolucionId ya está asociado a otra reserva o a otros detalles.");
        }
    }

    private RegistroDevolucion buscarTrasRollback(String devolucionId) {
        return transaccion.execute(status -> devolucionesRepository.findById(devolucionId).orElse(null));
    }

    private boolean asientoYaDevueltoTrasRollback(SolicitudNormalizada solicitud) {
        return transaccion.execute(status -> {
            Reserva reserva = reservasRepository.findById(solicitud.reservaId()).orElse(null);
            if (reserva == null) return false;
            for (DetalleDevolucionRequest solicitado : solicitud.detalles()) {
                if (solicitado.fila() == null) continue;
                for (DetalleReserva detalle : reserva.getDetalles()) {
                    if (solicitado.sectorId().equals(detalle.getSectorId())
                            && solicitado.fila().equals(detalle.getFila())
                            && solicitado.numero().equals(detalle.getNumero())
                            && devolucionesRepository.seatWasReturned(detalle.getId())) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private record SolicitudNormalizada(
            String reservaId,
            String devolucionId,
            List<DetalleDevolucionRequest> detalles,
            String huella) {
    }

    private record DetalleAplicado(DetalleReserva original, int cantidad) {
    }
}
