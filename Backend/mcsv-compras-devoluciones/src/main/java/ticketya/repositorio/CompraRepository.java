package ticketya.repositorio;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompraRepository extends JpaRepository<Compra, UUID> {

    List<Compra> findByClienteIdOrderByFechaDesc(String clienteId);

    Optional<Compra> findByReservaId(String reservaId);

    /** Las compras de un evento en un estado (E-1 usa las confirmadas). */
    List<Compra> findByEventoIdAndEstadoOrderByFechaAsc(String eventoId, EstadoCompra estado);

    /**
     * Lee la compra bloqueando su fila hasta el fin de la transacción: dos pagos de la
     * misma compra se procesan de a uno y el segundo ve lo que hizo el primero.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Compra c where c.id = :id")
    Optional<Compra> bloquear(@Param("id") UUID id);

    /** La compra con su detalle ya cargado. */
    @Query("select c from Compra c left join fetch c.detalle where c.id = :id")
    Optional<Compra> buscarConDetalle(@Param("id") UUID id);

    /**
     * Cambia el estado solo si la compra sigue en el estado esperado.
     * Devuelve 1 si cambió y 0 si otra ejecución ya la había resuelto.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Compra c set c.estado = :nuevo where c.id = :id and c.estado = :esperado")
    int cambiarEstado(@Param("id") UUID id,
                      @Param("esperado") EstadoCompra esperado,
                      @Param("nuevo") EstadoCompra nuevo);
}