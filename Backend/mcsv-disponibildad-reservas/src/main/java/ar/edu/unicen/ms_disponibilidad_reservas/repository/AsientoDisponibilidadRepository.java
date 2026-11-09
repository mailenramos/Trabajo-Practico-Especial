package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_disponibilidad_reservas.model.AsientoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;

public interface AsientoDisponibilidadRepository extends JpaRepository<AsientoDisponibilidad, Long> {

    boolean existsByEventoIdAndSectorIdAndFilaAndNumeroAndEstado(
            String eventoId, String sectorId, Integer fila, Integer numero, EstadoAsiento estado);

    long countByEventoIdAndSectorIdAndEstado(String eventoId, String sectorId, EstadoAsiento estado);

    long countByEventoIdAndSectorId(String eventoId, String sectorId);

    List<AsientoDisponibilidad> findAllByEventoIdAndEstado(String eventoId, EstadoAsiento estado);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE AsientoDisponibilidad a SET a.estado = :nuevoEstado "
            + "WHERE a.eventoId = :eventoId AND a.sectorId = :sectorId "
            + "AND a.fila = :fila AND a.numero = :numero AND a.estado = :estadoAnterior")
    int transicionarEstado(@Param("eventoId") String eventoId,
            @Param("sectorId") String sectorId,
            @Param("fila") Integer fila,
            @Param("numero") Integer numero,
            @Param("estadoAnterior") EstadoAsiento estadoAnterior,
            @Param("nuevoEstado") EstadoAsiento nuevoEstado);
}
