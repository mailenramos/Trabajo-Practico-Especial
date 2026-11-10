package ticketya.cliente;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Servicio 4 (pagos). Los nombres de campos (en inglés) y de estados (en español)
 * respetan el contrato acordado con Pagos.
 */
public interface PagosCliente {

    /**
     * Pide el cobro. La idempotencyKey es la claveCobro que genera este servicio, nunca
     * la clave del cliente. Un rechazo llega como respuesta normal con status RECHAZADO.
     *
     * @throws ConflictoEnPagosException si Pagos responde 409 (por ejemplo, la compra ya
     *                                   tiene un pago aprobado con otra clave)
     * @throws ServicioExternoException  si no hubo respuesta: el cobro queda incierto
     */
    Pago cobrar(String idempotencyKey, String purchaseId, BigDecimal amount, String paymentMethod);

    /**
     * Pagos registrados para la compra (aprobados o rechazados). Lista vacía si Pagos no
     * tiene ninguno: la consulta por compra responde 200 con [] y nunca 404.
     */
    List<Pago> listarPagosPorCompra(String purchaseId);

    /** Pide un reintegro. La idempotencyKey es el mismo valor que el refundRequestId. */
    Reintegro reintegrar(String paymentId, String idempotencyKey, String refundRequestId,
                         BigDecimal amount, String reason);

    /** Reintenta un reintegro FALLIDO, sin cuerpo ni clave nueva. */
    Reintegro reintentarReintegro(String refundId);

    Optional<Reintegro> consultarReintegro(String refundId);

    /** Estados del cobro según Pagos. EN_PROCESO existe en su enum pero nunca se guarda. */
    enum EstadoPago { APROBADO, RECHAZADO, EN_PROCESO }

    enum EstadoReintegroPagos { EXITOSO, FALLIDO }

    record Pago(String paymentId, String purchaseId, BigDecimal amount, EstadoPago status,
                String paymentMethod) {
    }

    record Reintegro(String id, String paymentId, String refundRequestId, BigDecimal amount,
                     EstadoReintegroPagos status, String reason, Integer attemptCount,
                     Instant createdAt, Instant updatedAt) {
    }

    /** Pagos respondió 409 Conflict. */
    class ConflictoEnPagosException extends RuntimeException {
        public ConflictoEnPagosException(String mensaje) {
            super(mensaje);
        }
    }
}
