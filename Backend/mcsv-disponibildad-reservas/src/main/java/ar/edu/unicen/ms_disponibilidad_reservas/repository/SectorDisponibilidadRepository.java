package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;

public interface SectorDisponibilidadRepository extends JpaRepository<SectorDisponibilidad, Long> {

    Optional<SectorDisponibilidad> findByEventoIdAndSectorId(String eventoId, String sectorId);

    List<SectorDisponibilidad> findAllByEventoId(String eventoId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE SectorDisponibilidad s SET s.cantidadReservada = s.cantidadReservada + :cantidad "
            + "WHERE s.eventoId = :eventoId AND s.sectorId = :sectorId AND s.tipo = :tipo "
            + "AND s.cantidadReservada >= 0 AND s.cantidadVendida >= 0 "
            + "AND s.cantidadReservada + s.cantidadVendida + :cantidad <= s.capacidadTotal")
    int reservarCantidad(@Param("eventoId") String eventoId,
            @Param("sectorId") String sectorId,
            @Param("tipo") TipoSector tipo,
            @Param("cantidad") int cantidad);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE SectorDisponibilidad s SET s.cantidadReservada = s.cantidadReservada - :cantidad, "
            + "s.cantidadVendida = s.cantidadVendida + :cantidad "
            + "WHERE s.eventoId = :eventoId AND s.sectorId = :sectorId AND s.tipo = :tipo "
            + "AND s.cantidadReservada >= :cantidad AND s.cantidadVendida >= 0 "
            + "AND s.cantidadReservada + s.cantidadVendida <= s.capacidadTotal")
    int confirmarCantidad(@Param("eventoId") String eventoId,
            @Param("sectorId") String sectorId,
            @Param("tipo") TipoSector tipo,
            @Param("cantidad") int cantidad);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE SectorDisponibilidad s SET s.cantidadReservada = s.cantidadReservada - :cantidad "
            + "WHERE s.eventoId = :eventoId AND s.sectorId = :sectorId AND s.tipo = :tipo "
            + "AND s.cantidadReservada >= :cantidad AND s.cantidadVendida >= 0 "
            + "AND s.cantidadReservada + s.cantidadVendida <= s.capacidadTotal")
    int liberarCantidadReservada(@Param("eventoId") String eventoId,
            @Param("sectorId") String sectorId,
            @Param("tipo") TipoSector tipo,
            @Param("cantidad") int cantidad);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE SectorDisponibilidad s SET s.cantidadVendida = s.cantidadVendida - :cantidad "
            + "WHERE s.eventoId = :eventoId AND s.sectorId = :sectorId AND s.tipo = :tipo "
            + "AND s.cantidadVendida >= :cantidad AND s.cantidadReservada >= 0 "
            + "AND s.cantidadReservada + s.cantidadVendida <= s.capacidadTotal")
    int devolverCantidadVendida(@Param("eventoId") String eventoId,
            @Param("sectorId") String sectorId,
            @Param("tipo") TipoSector tipo,
            @Param("cantidad") int cantidad);
}
