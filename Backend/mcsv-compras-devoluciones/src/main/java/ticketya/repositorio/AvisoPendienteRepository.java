package ticketya.repositorio;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.TipoAviso;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AvisoPendienteRepository extends JpaRepository<AvisoPendiente, Long> {

    /**
     * Guarda el aviso PENDIENTE dentro de la transacción de quien llama (MANDATORY: sin
     * transacción falla). Si ya existe uno con el mismo acontecimiento, tipo y destinatario no
     * hace nada y no lanza error: así un aviso repetido nunca revierte la operación de negocio.
     * No limpia el contexto de persistencia de quien llama.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into avisos_pendientes
                (acontecimiento_id, tipo, usuario_id, recurso_id, correlacion_id, datos,
                 estado, intentos, proximo_intento, creado_en)
            values (:acontecimientoId, :tipo, :usuarioId, :recursoId, :correlacionId, :datos,
                    'PENDIENTE', 0, :ahora, :ahora)
            on duplicate key update id = id""")
    void registrar(@Param("acontecimientoId") String acontecimientoId,
                   @Param("tipo") String tipo,
                   @Param("usuarioId") String usuarioId,
                   @Param("recursoId") String recursoId,
                   @Param("correlacionId") String correlacionId,
                   @Param("datos") String datos,
                   @Param("ahora") Instant ahora);

    Optional<AvisoPendiente> findByAcontecimientoIdAndTipoAndUsuarioId(String acontecimientoId, TipoAviso tipo,
                                                                         String usuarioId);

    List<AvisoPendiente> findByTipoOrderByIdAsc(TipoAviso tipo);

    /** Avisos PENDIENTES cuyo próximo intento ya llegó, del más atrasado al más nuevo. */
    @Query("""
            select a.id from AvisoPendiente a
            where a.estado = ticketya.modelo.EstadoAviso.PENDIENTE and a.proximoIntento <= :ahora
            order by a.proximoIntento asc, a.id asc""")
    List<Long> idsListosParaEnviar(@Param("ahora") Instant ahora, Limit limite);

    /**
     * Toma el aviso para enviarlo: pasa de PENDIENTE a PROCESANDO solo si sigue pendiente y listo.
     * Entre dos ejecuciones (o dos instancias) solo una lo toma: devuelve 1 a esa y 0 a la otra.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AvisoPendiente a
            set a.estado = ticketya.modelo.EstadoAviso.PROCESANDO, a.toma = :toma, a.venceEn = :vence
            where a.id = :id and a.estado = ticketya.modelo.EstadoAviso.PENDIENTE and a.proximoIntento <= :ahora""")
    int tomar(@Param("id") Long id, @Param("toma") String toma, @Param("ahora") Instant ahora,
              @Param("vence") Instant vence);

    /** Envío confirmado. Solo si sigue PROCESANDO con esta toma: si no, devuelve 0 y no pisa nada. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AvisoPendiente a
            set a.estado = ticketya.modelo.EstadoAviso.ENVIADO, a.enviadoEn = :ahora, a.ultimoError = null,
                a.toma = null, a.venceEn = null
            where a.id = :id and a.estado = ticketya.modelo.EstadoAviso.PROCESANDO and a.toma = :toma""")
    int marcarEnviado(@Param("id") Long id, @Param("toma") String toma, @Param("ahora") Instant ahora);

    /** Falla temporal: vuelve a PENDIENTE para el próximo intento. Misma condición que {@link #marcarEnviado}. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AvisoPendiente a
            set a.estado = ticketya.modelo.EstadoAviso.PENDIENTE, a.intentos = :intentos,
                a.proximoIntento = :proximo, a.ultimoError = :error, a.toma = null, a.venceEn = null
            where a.id = :id and a.estado = ticketya.modelo.EstadoAviso.PROCESANDO and a.toma = :toma""")
    int reprogramar(@Param("id") Long id, @Param("toma") String toma, @Param("intentos") int intentos,
                    @Param("proximo") Instant proximo, @Param("error") String error);

    /** Rechazo definitivo o intentos agotados: no se reintenta más. Misma condición que {@link #marcarEnviado}. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AvisoPendiente a
            set a.estado = ticketya.modelo.EstadoAviso.ERROR_FINAL, a.intentos = :intentos,
                a.ultimoError = :error, a.toma = null, a.venceEn = null
            where a.id = :id and a.estado = ticketya.modelo.EstadoAviso.PROCESANDO and a.toma = :toma""")
    int marcarErrorFinal(@Param("id") Long id, @Param("toma") String toma, @Param("intentos") int intentos,
                         @Param("error") String error);

    /**
     * Recuperación, primer paso: los PROCESANDO vencidos que con este intento agotan el máximo
     * pasan a ERROR_FINAL. Se corre antes de {@link #recuperarVencidosComoPendientes}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AvisoPendiente a
            set a.estado = ticketya.modelo.EstadoAviso.ERROR_FINAL, a.intentos = a.intentos + 1,
                a.ultimoError = :error, a.toma = null, a.venceEn = null
            where a.estado = ticketya.modelo.EstadoAviso.PROCESANDO and a.venceEn < :ahora
              and a.intentos + 1 >= :intentosMaximos""")
    int recuperarVencidosComoErrorFinal(@Param("ahora") Instant ahora, @Param("intentosMaximos") int intentosMaximos,
                                        @Param("error") String error);

    /**
     * Recuperación, segundo paso: los demás PROCESANDO vencidos vuelven a PENDIENTE, contando el
     * intento interrumpido, listos para el próximo envío. Al borrar la toma, el resultado tardío de
     * un envío lento ya no se guarda.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AvisoPendiente a
            set a.estado = ticketya.modelo.EstadoAviso.PENDIENTE, a.intentos = a.intentos + 1,
                a.proximoIntento = :ahora, a.ultimoError = :error, a.toma = null, a.venceEn = null
            where a.estado = ticketya.modelo.EstadoAviso.PROCESANDO and a.venceEn < :ahora""")
    int recuperarVencidosComoPendientes(@Param("ahora") Instant ahora, @Param("error") String error);
}
