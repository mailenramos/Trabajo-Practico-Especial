package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
import jakarta.persistence.LockModeType;

public interface ReservaRepository extends JpaRepository<Reserva, String> {

    /** Current locking read: the event must already be locked before this query. */
    @Query(value = "SELECT d.cantidad FROM reservas r JOIN detalles_reserva d ON d.reserva_id = r.id "
            + "WHERE r.usuario_id = :usuarioId AND r.evento_id = :eventoId "
            + "AND r.estado = 'VIGENTE' AND r.expira_en <= :limite ORDER BY r.id, d.id FOR UPDATE",
            nativeQuery = true)
    List<Integer> findUnidadesVencidasForUpdate(@Param("usuarioId") String usuarioId,
            @Param("eventoId") String eventoId, @Param("limite") LocalDateTime limite);

    @Query("SELECT r.id FROM Reserva r WHERE r.estado = :estado AND r.expiraEn <= :limite "
            + "ORDER BY r.expiraEn, r.id")
    List<String> findIdsVigentesVencidasHasta(
            @Param("estado") EstadoReserva estado, @Param("limite") LocalDateTime limite);

    @Query("SELECT r.id FROM Reserva r WHERE r.eventoId = :eventoId AND r.estado = :estado ORDER BY r.id")
    List<String> findIdsByEventoIdAndEstado(
            @Param("eventoId") String eventoId, @Param("estado") EstadoReserva estado);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reserva r WHERE r.id = :reservaId")
    Optional<Reserva> findByIdForUpdate(@Param("reservaId") String reservaId);

    /** Reads only the immutable event key, avoiding a stale managed Reservation before the locking read. */
    @Query("SELECT r.eventoId FROM Reserva r WHERE r.id = :reservaId")
    Optional<String> findEventoIdById(@Param("reservaId") String reservaId);

    Optional<Reserva> findByUsuarioIdAndClaveIdempotencia(String usuarioId, String claveIdempotencia);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reserva r WHERE r.usuarioId = :usuarioId AND r.claveIdempotencia = :clave")
    Optional<Reserva> findByUsuarioIdAndClaveIdempotenciaForUpdate(
            @Param("usuarioId") String usuarioId, @Param("clave") String claveIdempotencia);
}
