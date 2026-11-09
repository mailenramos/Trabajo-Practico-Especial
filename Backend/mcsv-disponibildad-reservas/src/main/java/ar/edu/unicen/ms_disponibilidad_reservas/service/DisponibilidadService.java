package ar.edu.unicen.ms_disponibilidad_reservas.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoDisponibleResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DisponibilidadEventoResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorDisponibilidadResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.model.AsientoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.DetalleReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;
import jakarta.persistence.EntityManager;

@Service
public class DisponibilidadService {

    private final AsientoDisponibilidadRepository asientoRepository;
    private final SectorDisponibilidadRepository sectorRepository;
    private final EntityManager entityManager;

    public DisponibilidadService(AsientoDisponibilidadRepository asientoRepository,
            SectorDisponibilidadRepository sectorRepository,
            EntityManager entityManager) {
        this.asientoRepository = asientoRepository;
        this.sectorRepository = sectorRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public int consultarDisponibilidad(String eventoId, String sectorId) {
        SectorDisponibilidad sector = obtenerSector(eventoId, sectorId);
        return calcularDisponibilidad(sector);
    }

    @Transactional(readOnly = true)
    public DisponibilidadEventoResponse consultarDisponibilidadEvento(String eventoId) {
        List<SectorDisponibilidad> sectores = sectorRepository.findAllByEventoId(eventoId);
        if (sectores.isEmpty()) {
            throw new DisponibilidadNoRegistradaException(
                    "No existe disponibilidad registrada para el evento " + eventoId + " en MS2");
        }

        Map<String, List<AsientoDisponibilidad>> asientosLibresPorSector = asientoRepository
                .findAllByEventoIdAndEstado(eventoId, EstadoAsiento.LIBRE)
                .stream()
                .collect(Collectors.groupingBy(AsientoDisponibilidad::getSectorId));

        List<SectorDisponibilidadResponse> respuestas = sectores.stream()
                .map(sector -> {
                    if (sector.getTipo() == TipoSector.NUMERADO) {
                        List<AsientoDisponibleResponse> disponibles = asientosLibresPorSector
                                .getOrDefault(sector.getSectorId(), List.of())
                                .stream()
                                .sorted(Comparator.comparing(AsientoDisponibilidad::getFila)
                                        .thenComparing(AsientoDisponibilidad::getNumero))
                                .map(asiento -> new AsientoDisponibleResponse(asiento.getFila(), asiento.getNumero()))
                                .toList();
                        return new SectorDisponibilidadResponse(sector.getSectorId(), sector.getTipo(),
                                sector.getCapacidadTotal(), disponibles.size(), disponibles);
                    }

                    return new SectorDisponibilidadResponse(sector.getSectorId(), sector.getTipo(),
                            sector.getCapacidadTotal(), calcularDisponibilidadNoNumerada(sector), null);
                })
                .toList();

        return new DisponibilidadEventoResponse(eventoId, respuestas);
    }

    private int calcularDisponibilidad(SectorDisponibilidad sector) {
        if (sector.getTipo() == TipoSector.NUMERADO) {
            return Math.toIntExact(asientoRepository.countByEventoIdAndSectorIdAndEstado(
                    sector.getEventoId(), sector.getSectorId(), EstadoAsiento.LIBRE));
        }
        return calcularDisponibilidadNoNumerada(sector);
    }

    private int calcularDisponibilidadNoNumerada(SectorDisponibilidad sector) {
        int disponibles = sector.getCapacidadTotal() - sector.getCantidadReservada() - sector.getCantidadVendida();
        if (disponibles < 0) {
            throw new DisponibilidadException("Las cantidades del sector superan su capacidad total");
        }
        return disponibles;
    }

    @Transactional(rollbackFor = Exception.class)
    public void reservar(Collection<DetalleReserva> detalles) {
        aplicarTransicion(detalles, EstadoAsiento.LIBRE, EstadoAsiento.RESERVADO, Accion.RESERVAR);
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmar(Collection<DetalleReserva> detalles) {
        aplicarTransicion(detalles, EstadoAsiento.RESERVADO, EstadoAsiento.VENDIDO, Accion.CONFIRMAR);
    }

    @Transactional(rollbackFor = Exception.class)
    public void liberar(Collection<DetalleReserva> detalles) {
        aplicarTransicion(detalles, EstadoAsiento.RESERVADO, EstadoAsiento.LIBRE, Accion.LIBERAR);
    }

    @Transactional(rollbackFor = Exception.class)
    public void devolver(Collection<DetalleReserva> detalles) {
        aplicarTransicion(detalles, EstadoAsiento.VENDIDO, EstadoAsiento.LIBRE, Accion.DEVOLVER);
    }

    private void aplicarTransicion(Collection<DetalleReserva> detalles,
            EstadoAsiento estadoAnterior,
            EstadoAsiento nuevoEstado,
            Accion accion) {
        InventarioSolicitado solicitado = agrupar(detalles);

        // El orden estable reduce los interbloqueos entre solicitudes que se cruzan.
        for (ClaveAsiento clave : solicitado.asientos.keySet()) {
            int actualizados = asientoRepository.transicionarEstado(
                    clave.eventoId, clave.sectorId, clave.fila, clave.numero, estadoAnterior, nuevoEstado);
            if (actualizados != 1) {
                throw new DisponibilidadException(
                        "El asiento " + clave + " no estaba en el estado esperado " + estadoAnterior);
            }
        }

        for (Map.Entry<ClaveSector, Integer> entrada : solicitado.sectoresNoNumerados.entrySet()) {
            ClaveSector clave = entrada.getKey();
            int cantidad = entrada.getValue();
            int actualizados = switch (accion) {
                case RESERVAR -> sectorRepository.reservarCantidad(
                        clave.eventoId, clave.sectorId, TipoSector.NO_NUMERADO, cantidad);
                case CONFIRMAR -> sectorRepository.confirmarCantidad(
                        clave.eventoId, clave.sectorId, TipoSector.NO_NUMERADO, cantidad);
                case LIBERAR -> sectorRepository.liberarCantidadReservada(
                        clave.eventoId, clave.sectorId, TipoSector.NO_NUMERADO, cantidad);
                case DEVOLVER -> sectorRepository.devolverCantidadVendida(
                        clave.eventoId, clave.sectorId, TipoSector.NO_NUMERADO, cantidad);
            };

            if (actualizados != 1) {
                throw new DisponibilidadException(
                        "El sector " + clave + " no tiene capacidad o cantidades suficientes para " + accion);
            }
            entityManager.refresh(solicitado.entidadesSector.get(clave));
        }
    }

    private InventarioSolicitado agrupar(Collection<DetalleReserva> detalles) {
        if (detalles == null || detalles.isEmpty()) {
            throw new DisponibilidadException("La operación requiere al menos un detalle de reserva");
        }

        TreeMap<ClaveAsiento, Boolean> asientos = new TreeMap<>();
        TreeMap<ClaveSector, Integer> sectores = new TreeMap<>();
        TreeMap<ClaveSector, SectorDisponibilidad> entidadesSector = new TreeMap<>();
        TreeMap<ClaveSector, TipoSector> tiposEsperados = new TreeMap<>();

        for (DetalleReserva detalle : detalles) {
            if (detalle == null || detalle.getReserva() == null || detalle.getReserva().getEventoId() == null
                    || detalle.getSectorId() == null || detalle.getCantidad() == null || detalle.getCantidad() <= 0) {
                throw new DisponibilidadException("El detalle de reserva no contiene una referencia válida");
            }

            String eventoId = detalle.getReserva().getEventoId();
            ClaveSector claveSector = new ClaveSector(eventoId, detalle.getSectorId());
            boolean tieneFila = detalle.getFila() != null;
            boolean tieneNumero = detalle.getNumero() != null;
            if (tieneFila != tieneNumero) {
                throw new DisponibilidadException("El detalle numerado debe incluir fila y número");
            }

            if (tieneFila) {
                if (detalle.getNumero() < 1 || detalle.getCantidad() != 1) {
                    throw new DisponibilidadException("Un detalle numerado requiere número positivo y cantidad 1");
                }
                ClaveAsiento clave = new ClaveAsiento(
                        eventoId, detalle.getSectorId(), detalle.getFila(), detalle.getNumero());
                if (asientos.putIfAbsent(clave, Boolean.TRUE) != null) {
                    throw new DisponibilidadException("La operación contiene el mismo asiento más de una vez: " + clave);
                }
                registrarTipoEsperado(tiposEsperados, claveSector, TipoSector.NUMERADO);
            } else {
                sectores.merge(claveSector, detalle.getCantidad(), Math::addExact);
                registrarTipoEsperado(tiposEsperados, claveSector, TipoSector.NO_NUMERADO);
            }
        }

        for (Map.Entry<ClaveSector, TipoSector> entrada : tiposEsperados.entrySet()) {
            SectorDisponibilidad sector = obtenerSector(entrada.getKey().eventoId, entrada.getKey().sectorId);
            if (sector.getTipo() != entrada.getValue()) {
                throw new DisponibilidadException("El detalle no corresponde al tipo del sector " + entrada.getKey());
            }
            entidadesSector.put(entrada.getKey(), sector);
        }

        return new InventarioSolicitado(asientos, sectores, entidadesSector);
    }

    private void registrarTipoEsperado(Map<ClaveSector, TipoSector> tipos,
            ClaveSector clave,
            TipoSector tipo) {
        TipoSector anterior = tipos.putIfAbsent(clave, tipo);
        if (anterior != null && anterior != tipo) {
            throw new DisponibilidadException("Un sector no puede mezclar detalles numerados y no numerados");
        }
    }

    private SectorDisponibilidad obtenerSector(String eventoId, String sectorId) {
        return sectorRepository.findByEventoIdAndSectorId(eventoId, sectorId)
                .orElseThrow(() -> new DisponibilidadException("No existe disponibilidad para el sector solicitado"));
    }

    private enum Accion {
        RESERVAR,
        CONFIRMAR,
        LIBERAR,
        DEVOLVER
    }

    private record ClaveAsiento(String eventoId, String sectorId, Integer fila, Integer numero)
            implements Comparable<ClaveAsiento> {
        @Override
        public int compareTo(ClaveAsiento otra) {
            int resultado = eventoId.compareTo(otra.eventoId);
            if (resultado == 0) resultado = sectorId.compareTo(otra.sectorId);
            if (resultado == 0) resultado = fila.compareTo(otra.fila);
            if (resultado == 0) resultado = numero.compareTo(otra.numero);
            return resultado;
        }
    }

    private record ClaveSector(String eventoId, String sectorId) implements Comparable<ClaveSector> {
        @Override
        public int compareTo(ClaveSector otra) {
            int resultado = eventoId.compareTo(otra.eventoId);
            if (resultado == 0) resultado = sectorId.compareTo(otra.sectorId);
            return resultado;
        }
    }

    private record InventarioSolicitado(TreeMap<ClaveAsiento, Boolean> asientos,
            TreeMap<ClaveSector, Integer> sectoresNoNumerados,
            TreeMap<ClaveSector, SectorDisponibilidad> entidadesSector) {
    }
}
