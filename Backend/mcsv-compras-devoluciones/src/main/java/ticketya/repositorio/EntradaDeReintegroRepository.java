package ticketya.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketya.modelo.EntradaDeReintegro;

import java.util.List;
import java.util.UUID;

public interface EntradaDeReintegroRepository extends JpaRepository<EntradaDeReintegro, Long> {

    List<EntradaDeReintegro> findByReintegroIdOrderByIdAsc(UUID reintegroId);

    List<EntradaDeReintegro> findByReintegroIdAndDevueltaFalseOrderByIdAsc(UUID reintegroId);

    // ------------------------------------------------------------------ reportes

    /**
     * Entradas distintas devueltas por la cancelación del evento (incluidas en un reintegro por
     * cancelación de una compra CONFIRMADA y ya devueltas), por sector, con su precio histórico.
     */
    @Query("""
            select new ticketya.repositorio.TotalPorSector(d.sectorId, max(d.sectorNombre), count(distinct e.entradaId), sum(d.precioUnitario))
            from EntradaDeReintegro e
              join Reintegro r on r.id = e.reintegroId
              join Compra c on c.id = r.compraId
              join c.detalle d
            where d.entradaId = e.entradaId and c.eventoId = :eventoId
              and c.estado = ticketya.modelo.EstadoCompra.CONFIRMADA
              and r.origen = ticketya.modelo.OrigenReintegro.CANCELACION_EVENTO and e.devuelta = true
            group by d.sectorId
            order by d.sectorId""")
    List<TotalPorSector> devueltasPorCancelacion(@Param("eventoId") String eventoId);
}
