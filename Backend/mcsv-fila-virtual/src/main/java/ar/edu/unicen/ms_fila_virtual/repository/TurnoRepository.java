package ar.edu.unicen.ms_fila_virtual.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_fila_virtual.model.Turno;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;
import jakarta.persistence.LockModeType;

public interface TurnoRepository extends JpaRepository<Turno, Long> {

    @Query("""
            SELECT t FROM Turno t WHERE t.fila.id = :filaId AND t.usuarioId = :usuarioId
              AND t.estado IN (ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno.ESPERANDO,
                               ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno.HABILITADO)
            """)
    Optional<Turno> findActivo(@Param("filaId") Long filaId, @Param("usuarioId") String usuarioId);

    Optional<Turno> findFirstByFilaIdAndUsuarioIdOrderByIdDesc(Long filaId, String usuarioId);

    Optional<Turno> findByUsuarioIdAndSolicitudId(String usuarioId, String solicitudId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Turno t WHERE t.id = :id")
    Optional<Turno> findByIdForUpdate(@Param("id") Long id);

    long countByFilaIdAndEstadoAndIdLessThan(Long filaId, EstadoTurno estado, Long id);

    long countByFilaIdAndEstado(Long filaId, EstadoTurno estado);

    long countByFilaIdAndEstadoAndExpiraEnAfter(Long filaId, EstadoTurno estado, LocalDateTime ahora);

    List<Turno> findByFilaIdAndEstadoOrderByIdAsc(Long filaId, EstadoTurno estado, Pageable limite);

    List<Turno> findByFilaIdAndEstadoAndExpiraEnLessThanEqualOrderByExpiraEnAscIdAsc(
            Long filaId, EstadoTurno estado, LocalDateTime ahora);

    List<Turno> findByFilaIdAndEstadoInOrderByIdAsc(Long filaId, List<EstadoTurno> estados);
}
