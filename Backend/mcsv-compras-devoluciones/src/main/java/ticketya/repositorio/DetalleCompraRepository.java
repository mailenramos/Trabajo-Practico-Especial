package ticketya.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketya.modelo.DetalleCompra;

import java.util.Optional;

public interface DetalleCompraRepository extends JpaRepository<DetalleCompra, Long> {

    /** La fila de detalle de una entrada emitida, con su compra ya cargada. */
    @Query("select d from DetalleCompra d join fetch d.compra where d.entradaId = :entradaId")
    Optional<DetalleCompra> buscarPorEntrada(@Param("entradaId") String entradaId);
}
