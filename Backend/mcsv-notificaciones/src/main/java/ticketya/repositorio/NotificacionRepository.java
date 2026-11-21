package ticketya.repositorio;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.Notificacion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificacionRepository extends JpaRepository<Notificacion, UUID> {

    Optional<Notificacion> findByClaveDeduplicacion(String claveDeduplicacion);

    /** Candidatas a enviar: PENDIENTE cuyo próximo intento ya llegó, las más atrasadas primero. */
    @Query("""
            select n.id from Notificacion n
            where n.estado = ticketya.modelo.EstadoNotificacion.PENDIENTE and n.proximoIntento <= :ahora
            order by n.proximoIntento""")
    List<UUID> idsListosParaEnviar(@Param("ahora") Instant ahora, Pageable lote);

    /**
     * Toma la notificación para enviarla: pasa de PENDIENTE a PROCESANDO solo si sigue PENDIENTE.
     * Devuelve 1 si esta llamada la tomó y 0 si otra instancia ya lo hizo; solo procesa quien obtiene 1.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notificacion n
            set n.estado = ticketya.modelo.EstadoNotificacion.PROCESANDO, n.venceProcesamiento = :vence
            where n.id = :id and n.estado = ticketya.modelo.EstadoNotificacion.PENDIENTE""")
    int tomar(@Param("id") UUID id, @Param("vence") Instant vence);

    /*
     * Guardado del resultado de un envío. Todas son actualizaciones condicionales: solo aplican si
     * la notificación sigue en PROCESANDO con el mismo vencimiento que fijó la toma (identifica
     * esa toma). Si la recuperación la liberó, o otra instancia la volvió a tomar, afectan 0 filas
     * y no pisan nada.
     */

    /** SMTP aceptó el correo. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notificacion n
            set n.estado = ticketya.modelo.EstadoNotificacion.ENVIADA, n.intentos = n.intentos + 1,
                n.enviadaEn = :enviadaEn, n.venceProcesamiento = null, n.ultimoError = null
            where n.id = :id and n.estado = ticketya.modelo.EstadoNotificacion.PROCESANDO
              and n.venceProcesamiento = :venceToma""")
    int guardarEnviada(@Param("id") UUID id, @Param("venceToma") Instant venceToma,
                       @Param("enviadaEn") Instant enviadaEn);

    /** Fallo temporal con intentos restantes: vuelve a PENDIENTE para el próximo intento. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notificacion n
            set n.estado = ticketya.modelo.EstadoNotificacion.PENDIENTE, n.intentos = n.intentos + 1,
                n.proximoIntento = :proximoIntento, n.venceProcesamiento = null, n.ultimoError = :error
            where n.id = :id and n.estado = ticketya.modelo.EstadoNotificacion.PROCESANDO
              and n.venceProcesamiento = :venceToma""")
    int guardarReintento(@Param("id") UUID id, @Param("venceToma") Instant venceToma,
                         @Param("proximoIntento") Instant proximoIntento, @Param("error") String error);

    /** Error definitivo o intentos agotados: ERROR_FINAL con el último error. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notificacion n
            set n.estado = ticketya.modelo.EstadoNotificacion.ERROR_FINAL, n.intentos = n.intentos + 1,
                n.venceProcesamiento = null, n.ultimoError = :error
            where n.id = :id and n.estado = ticketya.modelo.EstadoNotificacion.PROCESANDO
              and n.venceProcesamiento = :venceToma""")
    int guardarErrorFinal(@Param("id") UUID id, @Param("venceToma") Instant venceToma, @Param("error") String error);

    /**
     * Recuperación: las PROCESANDO vencidas a las que les queda al menos un intento vuelven a
     * PENDIENTE contando el intento interrumpido. Actualización condicional: si dos instancias
     * la corren a la vez, cada fila se recupera una sola vez.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notificacion n
            set n.estado = ticketya.modelo.EstadoNotificacion.PENDIENTE, n.intentos = n.intentos + 1,
                n.proximoIntento = :ahora, n.venceProcesamiento = null, n.ultimoError = :error
            where n.estado = ticketya.modelo.EstadoNotificacion.PROCESANDO and n.venceProcesamiento < :ahora
              and n.intentos + 1 < :maximoIntentos""")
    int devolverVencidasAPendiente(@Param("ahora") Instant ahora, @Param("maximoIntentos") int maximoIntentos,
                                   @Param("error") String error);

    /** Recuperación: las PROCESANDO vencidas que agotan los intentos con este pasan a ERROR_FINAL. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notificacion n
            set n.estado = ticketya.modelo.EstadoNotificacion.ERROR_FINAL, n.intentos = n.intentos + 1,
                n.venceProcesamiento = null, n.ultimoError = :error
            where n.estado = ticketya.modelo.EstadoNotificacion.PROCESANDO and n.venceProcesamiento < :ahora
              and n.intentos + 1 >= :maximoIntentos""")
    int pasarVencidasAErrorFinal(@Param("ahora") Instant ahora, @Param("maximoIntentos") int maximoIntentos,
                                 @Param("error") String error);
}
