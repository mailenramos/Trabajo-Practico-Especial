package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoNotificacionOutbox;
import ar.edu.unicen.ms_disponibilidad_reservas.model.NotificacionDisponibilidadOutbox;
import jakarta.persistence.LockModeType;

public interface NotificacionDisponibilidadOutboxRepository
        extends JpaRepository<NotificacionDisponibilidadOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM NotificacionDisponibilidadOutbox n "
            + "WHERE ((n.estado = :pendiente AND n.proximoIntentoEn <= :ahora) "
            + "OR (n.estado = :procesando AND n.leaseHasta <= :ahora)) "
            + "AND NOT EXISTS (SELECT anterior.id FROM NotificacionDisponibilidadOutbox anterior "
            + "WHERE anterior.eventoId = n.eventoId AND anterior.id < n.id "
            + "AND anterior.estado <> :enviada) ORDER BY n.id")
    List<NotificacionDisponibilidadOutbox> reclamarSiguienteDisponible(
            @Param("ahora") LocalDateTime ahora,
            @Param("pendiente") EstadoNotificacionOutbox pendiente,
            @Param("procesando") EstadoNotificacionOutbox procesando,
            @Param("enviada") EstadoNotificacionOutbox enviada,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM NotificacionDisponibilidadOutbox n WHERE n.id = :id")
    java.util.Optional<NotificacionDisponibilidadOutbox> findByIdForUpdate(@Param("id") long id);
}
