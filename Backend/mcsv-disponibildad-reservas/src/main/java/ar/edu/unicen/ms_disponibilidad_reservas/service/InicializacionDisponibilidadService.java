package ar.edu.unicen.ms_disponibilidad_reservas.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.model.AsientoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

/** Persiste atomicamente una inicialización recibida desde MS1. */
@Service
public class InicializacionDisponibilidadService {

    private static final String HUELLA_VERSION = "v2:";
    private static final String HUELLA_VERSION_EN_PREIMAGEN = "MS2-INIT-v2";

    private final EventoDisponibilidadRepository eventosRepository;
    private final SectorDisponibilidadRepository sectoresRepository;
    private final AsientoDisponibilidadRepository asientosRepository;
    private final Validator validator;
    private final TransactionTemplate transaccion;

    public InicializacionDisponibilidadService(
            EventoDisponibilidadRepository eventosRepository,
            SectorDisponibilidadRepository sectoresRepository,
            AsientoDisponibilidadRepository asientosRepository,
            Validator validator,
            PlatformTransactionManager transactionManager) {
        this.eventosRepository = eventosRepository;
        this.sectoresRepository = sectoresRepository;
        this.asientosRepository = asientosRepository;
        this.validator = validator;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Persiste una inicialización completa o, si ya existe el evento, devuelve el
     * resultado previo cuando la huella del contenido coincide. Cada intento usa
     * su propia transacción para poder resolver correctamente una carrera de inserción.
     */
    public EventoDisponibilidad inicializar(String eventoId, InicializarDisponibilidadRequest request) {
        validar(eventoId, request);
        String huella = calcularHuella(eventoId, request);

        try {
            return transaccion.execute(status -> inicializarEnTransaccion(eventoId, request, huella));
        } catch (DataIntegrityViolationException excepcionIntegridad) {
            // Si otra transacción ganó la inserción de eventoId, su commit ya terminó
            // antes de que la restricción única rechazara este intento. Se relee fuera
            // de la transacción fallida y se compara contra el contenido original.
            EventoDisponibilidad existente = transaccion.execute(status ->
                    eventosRepository.findByEventoId(eventoId).orElse(null));
            if (existente != null) {
                return devolverSiCoincide(existente, huella);
            }
            throw excepcionIntegridad;
        }
    }

    private EventoDisponibilidad inicializarEnTransaccion(
            String eventoId, InicializarDisponibilidadRequest request, String huella) {
        EventoDisponibilidad existente = eventosRepository.findByEventoId(eventoId).orElse(null);
        if (existente != null) {
            return devolverSiCoincide(existente, huella);
        }

        EventoDisponibilidad evento = new EventoDisponibilidad();
        evento.setEventoId(eventoId);
        evento.setEstadoEvento(EstadoEvento.PENDIENTE_PUBLICACION);
        evento.setVentaSuspendida(false);
        evento.setLimiteEntradasPorCliente(request.limiteEntradasPorCliente());
        evento.setHuellaInicializacion(huella);
        evento = eventosRepository.saveAndFlush(evento);

        List<SectorDisponibilidad> sectores = new ArrayList<>();
        List<AsientoDisponibilidad> asientos = new ArrayList<>();
        for (SectorInicializacionRequest sectorRequest : request.sectores()) {
            SectorDisponibilidad sector = new SectorDisponibilidad();
            sector.setEventoId(eventoId);
            sector.setSectorId(sectorRequest.sectorId());
            sector.setTipo(sectorRequest.tipo());
            sector.setCapacidadTotal(sectorRequest.capacidadTotal());
            sector.setCantidadReservada(0);
            sector.setCantidadVendida(0);
            sectores.add(sector);

            if (sectorRequest.tipo() == TipoSector.NUMERADO) {
                for (AsientoInicializacionRequest asientoRequest : sectorRequest.asientos()) {
                    AsientoDisponibilidad asiento = new AsientoDisponibilidad();
                    asiento.setEventoId(eventoId);
                    asiento.setSectorId(sectorRequest.sectorId());
                    asiento.setFila(asientoRequest.fila());
                    asiento.setNumero(asientoRequest.numero());
                    asiento.setEstado(EstadoAsiento.LIBRE);
                    asientos.add(asiento);
                }
            }
        }

        sectoresRepository.saveAllAndFlush(sectores);
        if (!asientos.isEmpty()) {
            asientosRepository.saveAllAndFlush(asientos);
        }
        return evento;
    }

    private EventoDisponibilidad devolverSiCoincide(EventoDisponibilidad existente, String huella) {
        String huellaPersistida = existente.getHuellaInicializacion();
        if (huellaPersistida == null || !huellaPersistida.startsWith(HUELLA_VERSION)) {
            throw new InicializacionConflictException(
                    "El evento tiene una huella de inicialización anterior o no verificable; no se puede confirmar que el reintento sea idéntico");
        }
        if (!huellaPersistida.equals(huella)) {
            throw new InicializacionConflictException(
                    "El evento ya tiene una inicialización con contenido diferente");
        }
        return existente;
    }

    private void validar(String eventoId, InicializarDisponibilidadRequest request) {
        if (eventoId == null || eventoId.isBlank() || eventoId.length() > 255) {
            throw new IllegalArgumentException("eventoId es obligatorio y debe tener como máximo 255 caracteres");
        }
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo de inicialización es obligatorio");
        }
        Set<ConstraintViolation<InicializarDisponibilidadRequest>> violaciones = validator.validate(request);
        if (!violaciones.isEmpty()) {
            throw new ConstraintViolationException(violaciones);
        }

        Set<String> sectorIds = new HashSet<>();
        for (SectorInicializacionRequest sector : request.sectores()) {
            if (!sectorIds.add(sector.sectorId())) {
                throw new IllegalArgumentException("El inventario contiene sectorId duplicado: " + sector.sectorId());
            }
            if (sector.tipo() == TipoSector.NUMERADO) {
                Set<ClaveAsiento> claves = new HashSet<>();
                for (AsientoInicializacionRequest asiento : sector.asientos()) {
                    if (!claves.add(new ClaveAsiento(asiento.fila(), asiento.numero()))) {
                        throw new IllegalArgumentException("El inventario contiene un lugar numerado duplicado en el sector "
                                + sector.sectorId());
                    }
                }
            } else if (!sector.asientos().isEmpty()) {
                throw new IllegalArgumentException(
                        "Un sector no numerado no puede incluir lugares individuales: " + sector.sectorId());
            }
        }
    }

    private String calcularHuella(String eventoId, InicializarDisponibilidadRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream salida = new DataOutputStream(bytes)) {
                salida.writeUTF(HUELLA_VERSION_EN_PREIMAGEN);
                salida.writeUTF(eventoId);
                salida.writeInt(request.limiteEntradasPorCliente());
                List<SectorInicializacionRequest> sectores = request.sectores().stream()
                        .sorted(Comparator.comparing(SectorInicializacionRequest::sectorId))
                        .toList();
                salida.writeInt(sectores.size());
                for (SectorInicializacionRequest sector : sectores) {
                    salida.writeUTF(sector.sectorId());
                    salida.writeUTF(sector.tipo().name());
                    salida.writeInt(sector.capacidadTotal());
                    List<AsientoInicializacionRequest> asientos = sector.asientos().stream()
                            .sorted(Comparator.comparing(AsientoInicializacionRequest::fila)
                                    .thenComparing(AsientoInicializacionRequest::numero))
                            .toList();
                    salida.writeInt(asientos.size());
                    for (AsientoInicializacionRequest asiento : asientos) {
                        salida.writeInt(asiento.fila());
                        salida.writeInt(asiento.numero());
                    }
                }
            }
            return HUELLA_VERSION
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException excepcion) {
            throw new IllegalStateException("No se pudo calcular la huella de inicialización", excepcion);
        }
    }

    private record ClaveAsiento(Integer fila, Integer numero) {
    }
}
