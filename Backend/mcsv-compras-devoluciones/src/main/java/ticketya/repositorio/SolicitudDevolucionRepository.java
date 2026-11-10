package ticketya.repositorio;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.SolicitudDevolucion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SolicitudDevolucionRepository extends JpaRepository<SolicitudDevolucion, UUID> {

    /** La solicitud pendiente o aprobada de la entrada, si la hay. */
    Optional<SolicitudDevolucion> findByEntradaActiva(String entradaId);

    List<SolicitudDevolucion> findByClienteIdOrderByCreadaEnDesc(String clienteId);

    List<SolicitudDevolucion> findByClienteIdAndEstadoOrderByCreadaEnDesc(String clienteId, EstadoSolicitud estado);

    List<SolicitudDevolucion> findAllByOrderByCreadaEnDesc();

    List<SolicitudDevolucion> findByEstadoOrderByCreadaEnDesc(EstadoSolicitud estado);

    /** Lee la solicitud bloqueando su fila hasta el fin de la transacción. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SolicitudDevolucion s where s.id = :id")
    Optional<SolicitudDevolucion> bloquear(@Param("id") UUID id);

    /**
     * Las solicitudes de una compra, leídas y bloqueadas: una aprobación simultánea espera a
     * que termine el procesamiento de la cancelación y después las ve resueltas.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SolicitudDevolucion s where s.compraId = :compraId order by s.creadaEn")
    List<SolicitudDevolucion> bloquearDeLaCompra(@Param("compraId") UUID compraId);

    /** Pasa a aprobada solo si sigue pendiente. Devuelve 1 si cambió y 0 si ya estaba resuelta. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SolicitudDevolucion s set s.estado = ticketya.modelo.EstadoSolicitud.APROBADA,
                   s.resueltaEn = :ahora, s.resueltaPor = :administrador
            where s.id = :id and s.estado = ticketya.modelo.EstadoSolicitud.PENDIENTE""")
    int aprobar(@Param("id") UUID id, @Param("administrador") String administrador, @Param("ahora") Instant ahora);

    /**
     * Pasa a rechazada solo si sigue pendiente, registra el motivo y libera la marca de
     * solicitud activa para que la entrada admita una solicitud nueva.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SolicitudDevolucion s set s.estado = ticketya.modelo.EstadoSolicitud.RECHAZADA,
                   s.motivoRechazo = :motivo, s.resueltaEn = :ahora, s.resueltaPor = :administrador,
                   s.entradaActiva = null
            where s.id = :id and s.estado = ticketya.modelo.EstadoSolicitud.PENDIENTE""")
    int rechazar(@Param("id") UUID id, @Param("motivo") String motivo,
                 @Param("administrador") String administrador, @Param("ahora") Instant ahora);
}
