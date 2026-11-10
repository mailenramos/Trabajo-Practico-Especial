package ticketya.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.ClaveIdempotencia;
import ticketya.modelo.ClaveIdempotenciaId;
import ticketya.modelo.EstadoClave;

import java.time.Instant;
import java.util.UUID;

public interface ClaveIdempotenciaRepository extends JpaRepository<ClaveIdempotencia, ClaveIdempotenciaId> {

    long countByCompraId(UUID compraId);

    /**
     * Vuelve a poner la clave EN_PROCESO solo si nadie la tomó desde que se leyó
     * (mismo estado y mismo tomadaEn). Devuelve 1 si este pedido la tomó y 0 si no.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ClaveIdempotencia k set k.estado = ticketya.modelo.EstadoClave.EN_PROCESO, k.tomadaEn = :ahora
            where k.id = :id and k.estado = :estadoLeido and k.tomadaEn = :tomadaEnLeida""")
    int retomar(@Param("id") ClaveIdempotenciaId id,
                @Param("estadoLeido") EstadoClave estadoLeido,
                @Param("tomadaEnLeida") Instant tomadaEnLeida,
                @Param("ahora") Instant ahora);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ClaveIdempotencia k set k.estado = :estado, k.resultado = :resultado where k.id = :id")
    int terminar(@Param("id") ClaveIdempotenciaId id,
                 @Param("estado") EstadoClave estado,
                 @Param("resultado") String resultado);
}
