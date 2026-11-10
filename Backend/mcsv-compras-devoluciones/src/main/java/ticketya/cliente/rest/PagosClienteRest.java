package ticketya.cliente.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import ticketya.cliente.PagosCliente;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Contrato acordado con Pagos (servicio interno, sin encabezados X-User-*). Campos en
 * inglés y estados en español, tal como los define Pagos. La consulta por compra
 * devuelve una lista (vacía si no hay pagos); el 404 es solo para un paymentId o un
 * reintegro inexistente. En los reintegros, reason es obligatorio.
 */
@Component
public class PagosClienteRest extends ClienteRest implements PagosCliente {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    public PagosClienteRest(@Value("${servicios.pagos.url}") String url,
                            @Value("${servicios.timeout-conexion:2s}") Duration conexion,
                            @Value("${servicios.timeout-lectura:5s}") Duration lectura) {
        super("Pagos", url, conexion, lectura);
    }

    @Override
    public Pago cobrar(String idempotencyKey, String purchaseId, BigDecimal amount, String paymentMethod) {
        return llamar("cobrar", () -> http.post().uri("/api/v1/payments")
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .body(new PedidoDeCobro(purchaseId, amount, paymentMethod))
                        .retrieve().body(Pago.class),
                (status, e) -> {
                    if (status.value() == 409) {
                        throw new ConflictoEnPagosException("Pagos rechazó el cobro por conflicto: "
                                + codigoDeError(e));
                    }
                    return null;
                });
    }

    @Override
    public List<Pago> listarPagosPorCompra(String purchaseId) {
        List<Pago> pagos = llamar("listarPagosPorCompra",
                () -> http.get().uri("/api/v1/payments/purchase/{id}", purchaseId)
                        .retrieve().body(new ParameterizedTypeReference<List<Pago>>() { }));
        return pagos == null ? List.of() : pagos;
    }

    @Override
    public Reintegro reintegrar(String paymentId, String idempotencyKey, String refundRequestId,
                                BigDecimal amount, String reason) {
        return llamar("reintegrar", () -> http.post().uri("/api/v1/payments/{id}/refunds", paymentId)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .body(new PedidoDeReintegro(refundRequestId, amount, reason))
                        .retrieve().body(Reintegro.class),
                this::conflicto);
    }

    @Override
    public Reintegro reintentarReintegro(String refundId) {
        return llamar("reintentarReintegro", () -> http.post().uri("/api/v1/refunds/{id}/retry", refundId)
                        .retrieve().body(Reintegro.class),
                this::conflicto);
    }

    @Override
    public Optional<Reintegro> consultarReintegro(String refundId) {
        return llamar("consultarReintegro",
                () -> Optional.ofNullable(http.get().uri("/api/v1/refunds/{id}", refundId)
                        .retrieve().body(Reintegro.class)),
                (status, e) -> status.value() == 404 ? Optional.empty() : null);
    }

    private Reintegro conflicto(org.springframework.http.HttpStatusCode status,
                                org.springframework.web.client.RestClientResponseException e) {
        if (status.value() == 409) {
            throw new ConflictoEnPagosException("Pagos respondió 409: " + codigoDeError(e));
        }
        return null;
    }

    record PedidoDeCobro(String purchaseId, BigDecimal amount, String paymentMethod) {
    }

    record PedidoDeReintegro(String refundRequestId, BigDecimal amount, String reason) {
    }
}
