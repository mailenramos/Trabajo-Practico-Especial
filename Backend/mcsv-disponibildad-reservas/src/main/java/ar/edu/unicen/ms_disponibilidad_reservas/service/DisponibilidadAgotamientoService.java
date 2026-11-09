package ar.edu.unicen.ms_disponibilidad_reservas.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;

import ar.edu.unicen.ms_disponibilidad_reservas.model.NotificacionDisponibilidadOutbox;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.NotificacionDisponibilidadOutboxRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;

/** Derives exhaustion from confirmed inventory and records only real transitions. */
@Service
public class DisponibilidadAgotamientoService {

    private final SectorDisponibilidadRepository sectoresRepository;
    private final AsientoDisponibilidadRepository asientosRepository;
    private final NotificacionDisponibilidadOutboxRepository outboxRepository;
    private final Clock clock;

    public DisponibilidadAgotamientoService(
            SectorDisponibilidadRepository sectoresRepository,
            AsientoDisponibilidadRepository asientosRepository,
            NotificacionDisponibilidadOutboxRepository outboxRepository,
            Clock clock) {
        this.sectoresRepository = sectoresRepository;
        this.asientosRepository = asientosRepository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    public boolean estaAgotado(String eventoId) {
        List<SectorDisponibilidad> sectores = sectoresRepository.findAllByEventoId(eventoId);
        if (sectores.isEmpty()) {
            throw new DisponibilidadNoRegistradaException(
                    "No hay disponibilidad registrada en MS2 para el evento solicitado.");
        }
        for (SectorDisponibilidad sector : sectores) {
            if (sector.getTipo() == TipoSector.NUMERADO) {
                long total = asientosRepository.countByEventoIdAndSectorId(eventoId, sector.getSectorId());
                long vendidos = asientosRepository.countByEventoIdAndSectorIdAndEstado(
                        eventoId, sector.getSectorId(), EstadoAsiento.VENDIDO);
                if (total != vendidos) return false;
            } else if (sector.getCantidadVendida() < sector.getCapacidadTotal()) {
                return false;
            }
        }
        return true;
    }

    /** Must run in the same transaction as the inventory mutation and with the event row locked. */
    public void registrarTransicionSiCambio(String eventoId, boolean agotadoAntes) {
        boolean agotadoDespues = estaAgotado(eventoId);
        if (agotadoAntes != agotadoDespues) {
            LocalDateTime ahoraUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            outboxRepository.saveAndFlush(
                    new NotificacionDisponibilidadOutbox(eventoId, agotadoDespues, ahoraUtc));
        }
    }
}
