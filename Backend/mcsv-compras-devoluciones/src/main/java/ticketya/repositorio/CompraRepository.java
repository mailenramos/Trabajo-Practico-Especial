package ticketya.repositorio;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;

import java.time.Instant;
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

    /**
     * Co-compras: los otros eventos con compras CONFIRMADAS de los clientes que tienen una compra
     * CONFIRMADA del evento indicado, con la cantidad de esos clientes (cada uno cuenta una vez),
     * de mayor a menor cantidad y, a igual cantidad, por eventoId. Las compras pendientes o
     * fallidas no cuentan en ningún lado.
     */
    @Query("""
            select new ticketya.repositorio.CoCompra(otra.eventoId, count(distinct otra.clienteId))
            from Compra otra
            where otra.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
              and otra.eventoId <> :eventoId
              and otra.clienteId in (
                  select c.clienteId from Compra c
                  where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA)
            group by otra.eventoId
            order by count(distinct otra.clienteId) desc, otra.eventoId asc""")
    List<CoCompra> coCompras(@Param("eventoId") String eventoId, Limit limite);

    /**
     * Historial de preferencias: los tipos de evento de las compras CONFIRMADAS del cliente, sin
     * repetir, con cuántas compras tiene de cada uno, de mayor a menor y, a igual cantidad, por
     * tipo. Las compras sin tipo (anteriores a la Entrega 2) no cuentan.
     */
    @Query("""
            select new ticketya.repositorio.CantidadPorTipo(c.tipoEvento, count(c))
            from Compra c
            where c.clienteId = :clienteId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
              and c.tipoEvento is not null
            group by c.tipoEvento
            order by count(c) desc, c.tipoEvento asc""")
    List<CantidadPorTipo> cantidadesPorTipo(@Param("clienteId") String clienteId);

    /**
     * Los eventos distintos de las compras CONFIRMADAS del cliente, del comprado más
     * recientemente al más antiguo (por la compra más reciente de cada evento). Incluye las
     * compras sin tipo de evento.
     */
    @Query("""
            select c.eventoId from Compra c
            where c.clienteId = :clienteId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
            group by c.eventoId
            order by max(c.fecha) desc, c.eventoId asc""")
    List<String> eventosComprados(@Param("clienteId") String clienteId, Limit limite);

    /**
     * Los clientes distintos con al menos una compra CONFIRMADA del evento (uno por cliente aunque
     * tenga varias compras), ordenados por clienteId. Son los compradores afectados por una
     * cancelación o una modificación del evento.
     */
    @Query("""
            select new ticketya.repositorio.CompradorDeEvento(c.clienteId, max(c.eventoNombre), max(c.eventoFechaInicio))
            from Compra c
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
            group by c.clienteId
            order by c.clienteId""")
    List<CompradorDeEvento> compradoresConfirmados(@Param("eventoId") String eventoId);

    /**
     * El evento cambió de fecha u horario: actualiza la fecha de inicio guardada en todas sus
     * compras, en cualquier estado. Devuelve cuántas compras cambió.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Compra c set c.eventoFechaInicio = :fechaInicio where c.eventoId = :eventoId")
    int actualizarFechaDelEvento(@Param("eventoId") String eventoId, @Param("fechaInicio") Instant fechaInicio);

    // ------------------------------------------------------------------ reportes

    /**
     * Compras PENDIENTES del evento que ya tienen pago (cobradas con la emisión sin terminar). Una
     * PENDIENTE sin pago es una compra iniciada que no se cobró: no es una operación pendiente.
     */
    @Query("""
            select count(c) from Compra c
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.PENDIENTE
              and c.pagoId is not null""")
    long contarCobradasSinConfirmar(@Param("eventoId") String eventoId);

    /** Ventas: entradas e importe histórico por sector, solo de compras CONFIRMADAS del evento. */
    @Query("""
            select new ticketya.repositorio.TotalPorSector(d.sectorId, max(d.sectorNombre), count(d), sum(d.precioUnitario))
            from Compra c join c.detalle d
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
            group by d.sectorId
            order by d.sectorId""")
    List<TotalPorSector> ventasPorSector(@Param("eventoId") String eventoId);

    long countByEventoIdAndEstado(String eventoId, EstadoCompra estado);

    /** Compras PENDIENTES con pago (cobradas con la emisión sin terminar): cantidad e importe. */
    @Query("""
            select new ticketya.repositorio.CantidadEImporte(count(c), sum(c.total))
            from Compra c
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.PENDIENTE
              and c.pagoId is not null""")
    CantidadEImporte cobradasSinConfirmar(@Param("eventoId") String eventoId);

    /** Compras cobradas que no están CONFIRMADAS (pendientes o fallidas): cantidad e importe cobrado. */
    @Query("""
            select new ticketya.repositorio.CantidadEImporte(count(c), sum(c.total))
            from Compra c
            where c.eventoId = :eventoId and c.estado <> ticketya.modelo.EstadoCompra.CONFIRMADA
              and c.pagoId is not null""")
    CantidadEImporte cobradasNoConfirmadas(@Param("eventoId") String eventoId);
}
