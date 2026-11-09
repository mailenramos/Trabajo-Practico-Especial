package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import jakarta.persistence.LockModeType;

public interface EventoDisponibilidadRepository extends JpaRepository<EventoDisponibilidad, Long> {

    Optional<EventoDisponibilidad> findByEventoId(String eventoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EventoDisponibilidad e WHERE e.eventoId = :eventoId")
    Optional<EventoDisponibilidad> findByEventoIdForUpdate(@Param("eventoId") String eventoId);
}
