package ticketya.repositorio;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.math.BigDecimal;
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

    // ------------------------------------------------------------------ reportes

    /** Reintegros PENDIENTES o FALLIDOS de compras del evento (en cualquier estado de la compra). */
    @Query("""
            select count(r) from Reintegro r join Compra c on c.id = r.compraId
            where c.eventoId = :eventoId
              and r.estado in (ticketya.modelo.EstadoReintegro.PENDIENTE, ticketya.modelo.EstadoReintegro.FALLIDO)""")
    long contarSinExito(@Param("eventoId") String eventoId);

    /** Reintegros de devoluciones de compras CONFIRMADAS, por sector de la solicitud y estado. */
    @Query("""
            select new ticketya.repositorio.ReintegroPorSector(s.sectorId, max(s.sectorNombre), r.estado, sum(r.monto))
            from Reintegro r
              join SolicitudDevolucion s on s.id = r.refundRequestId
              join Compra c on c.id = r.compraId
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
              and r.origen = ticketya.modelo.OrigenReintegro.DEVOLUCION
            group by s.sectorId, r.estado""")
    List<ReintegroPorSector> reintegrosDeDevolucionPorSector(@Param("eventoId") String eventoId);

    /**
     * Reintegros por cancelación de compras CONFIRMADAS repartidos por sector según el precio
     * histórico de cada entrada incluida (el monto del reintegro es la suma de esos precios).
     */
    @Query("""
            select new ticketya.repositorio.ReintegroPorSector(d.sectorId, max(d.sectorNombre), r.estado, sum(d.precioUnitario))
            from Reintegro r
              join EntradaDeReintegro e on e.reintegroId = r.id
              join Compra c on c.id = r.compraId
              join c.detalle d
            where d.entradaId = e.entradaId and c.eventoId = :eventoId
              and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
              and r.origen = ticketya.modelo.OrigenReintegro.CANCELACION_EVENTO
            group by d.sectorId, r.estado""")
    List<ReintegroPorSector> reintegrosDeCancelacionPorSector(@Param("eventoId") String eventoId);

    /** Reintegros de compras CONFIRMADAS del evento, por estado: cantidad e importe. */
    @Query("""
            select new ticketya.repositorio.ReintegrosPorEstado(r.estado, count(r), sum(r.monto))
            from Reintegro r join Compra c on c.id = r.compraId
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
            group by r.estado""")
    List<ReintegrosPorEstado> reintegrosDeConfirmadasPorEstado(@Param("eventoId") String eventoId);

    /** Importe ya reintegrado (EXITOSO) de compras del evento que no están CONFIRMADAS. */
    @Query("""
            select sum(r.monto) from Reintegro r join Compra c on c.id = r.compraId
            where c.eventoId = :eventoId and c.estado <> ticketya.modelo.EstadoCompra.CONFIRMADA
              and r.estado = ticketya.modelo.EstadoReintegro.EXITOSO""")
    BigDecimal reintegradoDeNoConfirmadas(@Param("eventoId") String eventoId);
}
