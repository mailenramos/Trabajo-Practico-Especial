package ar.edu.unicen.ms_fila_virtual.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_fila_virtual.model.FilaEvento;
import jakarta.persistence.LockModeType;

public interface FilaEventoRepository extends JpaRepository<FilaEvento, Long> {

    Optional<FilaEvento> findByEventoId(String eventoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FilaEvento f WHERE f.eventoId = :eventoId")
    Optional<FilaEvento> findByEventoIdForUpdate(@Param("eventoId") String eventoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FilaEvento f WHERE f.id = :id")
    Optional<FilaEvento> findByIdForUpdate(@Param("id") Long id);

    /** Candidates only: lock and recheck each fila before changing any turno. */
    @Query("""
            SELECT f.id FROM FilaEvento f
            WHERE EXISTS (SELECT t.id FROM Turno t WHERE t.fila = f
                          AND t.estado = ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno.HABILITADO
                          AND t.expiraEn <= :ahora)
               OR (f.estado = ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila.ACTIVA
                   AND f.cancelado = false
                   AND EXISTS (SELECT e.id FROM Turno e WHERE e.fila = f
                               AND e.estado = ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno.ESPERANDO)
                   AND (SELECT COUNT(h.id) FROM Turno h WHERE h.fila = f
                        AND h.estado = ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno.HABILITADO
                        AND h.expiraEn > :ahora) < f.cupo)
            ORDER BY f.id
            """)
    List<Long> findIdsParaProcesar(@Param("ahora") LocalDateTime ahora);
}
