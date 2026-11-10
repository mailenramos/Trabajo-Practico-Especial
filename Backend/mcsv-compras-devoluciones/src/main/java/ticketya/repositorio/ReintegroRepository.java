package ticketya.repositorio;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReintegroRepository extends JpaRepository<Reintegro, UUID> {

    Optional<Reintegro> findByRefundRequestId(UUID refundRequestId);

    List<Reintegro> findByCompraId(UUID compraId);

    List<Reintegro> findByCompraIdOrderByCreadoEnAsc(UUID compraId);

    /** Los reintegros de una compra con un origen (por cancelación hay a lo sumo uno). */
    List<Reintegro> findByCompraIdAndOrigen(UUID compraId, OrigenReintegro origen);

    List<Reintegro> findByEstado(EstadoReintegro estado);

    List<Reintegro> findByEstadoOrderByCreadoEnDesc(EstadoReintegro estado);

    List<Reintegro> findAllByOrderByCreadoEnDesc();

    /** Lee el reintegro bloqueando su fila: dos reintentos del mismo no avanzan a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reintegro r where r.id = :id")
    Optional<Reintegro> bloquear(@Param("id") UUID id);
}
