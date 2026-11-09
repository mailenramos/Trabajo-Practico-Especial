package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import jakarta.persistence.LockModeType;

public interface CantidadClienteEventoRepository extends JpaRepository<CantidadClienteEvento, Long> {

    Optional<CantidadClienteEvento> findByUsuarioIdAndEventoId(String usuarioId, String eventoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CantidadClienteEvento c "
            + "WHERE c.usuarioId = :usuarioId AND c.eventoId = :eventoId")
    Optional<CantidadClienteEvento> findByUsuarioIdAndEventoIdForUpdate(
            @Param("usuarioId") String usuarioId,
            @Param("eventoId") String eventoId);

    long countByUsuarioIdAndEventoId(String usuarioId, String eventoId);
}
