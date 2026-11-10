package ticketya.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.CancelacionEvento;
import ticketya.modelo.EstadoCancelacion;

import java.time.Instant;
import java.util.List;

public interface CancelacionEventoRepository extends JpaRepository<CancelacionEvento, String> {

    /** Cancelaciones en ese estado tomadas antes del límite (las vencidas, para reanudarlas). */
    List<CancelacionEvento> findByEstadoAndTomadaEnBefore(EstadoCancelacion estado, Instant limite);

    /**
     * Retoma una cancelación EN_PROCESO solo si nadie la tomó desde que se leyó (mismo
     * tomadaEn). Devuelve 1 si este pedido la tomó y 0 si no.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CancelacionEvento c set c.tomadaEn = :ahora
            where c.eventoId = :eventoId and c.estado = ticketya.modelo.EstadoCancelacion.EN_PROCESO
              and c.tomadaEn = :tomadaEnLeida""")
    int retomar(@Param("eventoId") String eventoId,
                @Param("tomadaEnLeida") Instant tomadaEnLeida,
                @Param("ahora") Instant ahora);

    /**
     * Marcación exitosa: suma lo que informó Entradas, borra el último error y deja la
     * marcación como hecha, para no volver a pedirla al retomar la cancelación.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CancelacionEvento c
            set c.entradasMarcadas = coalesce(c.entradasMarcadas, 0) + :marcadas, c.ultimoError = null,
                c.marcacionHecha = true
            where c.eventoId = :eventoId""")
    int registrarMarcacion(@Param("eventoId") String eventoId, @Param("marcadas") int marcadas);

    /** Marcación fallida: guarda el error y deja la marcación pendiente, para reintentarla al retomar. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CancelacionEvento c
            set c.entradasMarcadas = coalesce(c.entradasMarcadas, 0), c.ultimoError = :error
            where c.eventoId = :eventoId""")
    int registrarFallaDeMarcacion(@Param("eventoId") String eventoId, @Param("error") String error);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CancelacionEvento c set c.ultimoError = :error where c.eventoId = :eventoId")
    int registrarError(@Param("eventoId") String eventoId, @Param("error") String error);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CancelacionEvento c set c.estado = ticketya.modelo.EstadoCancelacion.PROCESADA
            where c.eventoId = :eventoId""")
    int terminar(@Param("eventoId") String eventoId);
}
