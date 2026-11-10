package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.cliente.PagosCliente.ConflictoEnPagosException;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.cliente.PagosCliente.Pago;
import ticketya.cliente.PagosCliente.Reintegro;
import ticketya.cliente.ServicioExternoException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * El cliente REST llama a Pagos por sus rutas internas (o por las configuradas), con los
 * mismos encabezados y cuerpos de siempre, y lee las respuestas como las define Pagos.
 */
class PagosClienteRestTest {

    private static final String PAGO = """
            {"paymentId":"p-1","purchaseId":"compra-1","amount":46001.00,"status":"APROBADO","paymentMethod":"CREDIT_CARD"}
            """;
    private static final String REINTEGRO = """
            {"id":"r-1","paymentId":"p-1","refundRequestId":"pedido-1","amount":10000.00,"status":"EXITOSO",
             "reason":"Devolución","attemptCount":1}
            """;

    private ServidorDePrueba servidor;
    private PagosClienteRest cliente;

    @BeforeEach
    void levantarServidor() throws IOException {
        servidor = new ServidorDePrueba();
        cliente = clienteCon(RutasPagos.internas());
    }

    @AfterEach
    void apagarServidor() {
        servidor.close();
    }

    private PagosClienteRest clienteCon(RutasPagos rutas) {
        return new PagosClienteRest(servidor.url(), Duration.ofSeconds(2), Duration.ofSeconds(2), rutas);
    }

    private String idempotencyKey() {
        return servidor.encabezados.getFirst("Idempotency-Key");
    }

    // ------------------------------------------------------------------ cobro

    @Test
    void cobraPorLaRutaInternaConLaClaveYElCuerpoDelContrato() {
        servidor.responder(201, PAGO);

        Pago pago = cliente.cobrar("clave-cobro-1", "compra-1", new BigDecimal("46001.00"), "CREDIT_CARD");

        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/pagos");
        assertThat(idempotencyKey()).isEqualTo("clave-cobro-1");
        assertThat(servidor.cuerpoRecibido).contains("\"purchaseId\":\"compra-1\"", "\"amount\":46001.00",
                "\"paymentMethod\":\"CREDIT_CARD\"");
        assertThat(pago.paymentId()).isEqualTo("p-1");
        assertThat(pago.status()).isEqualTo(EstadoPago.APROBADO);
    }

    @Test
    void unConflictoEnElCobroEsUnConflictoDePagos() {
        servidor.responder(409, "{\"error\":\"IDEMPOTENCIA_CONFLICTO\"}");

        assertThatThrownBy(() -> cliente.cobrar("clave-cobro-1", "compra-1", BigDecimal.TEN, "CREDIT_CARD"))
                .isInstanceOf(ConflictoEnPagosException.class)
                .hasMessageContaining("IDEMPOTENCIA_CONFLICTO");
    }

    // ------------------------------------------------------------------ pagos por compra

    @Test
    void sinPagosDevuelveUnaListaVacia() {
        servidor.responder(200, "[]");

        List<Pago> pagos = cliente.listarPagosPorCompra("compra-1");

        assertThat(pagos).isEmpty();
        assertThat(servidor.metodo).isEqualTo("GET");
        assertThat(servidor.ruta).isEqualTo("/internal/pagos/compra/compra-1");
    }

    @Test
    void conPagosLeeTodaLaListaConSusEstados() {
        servidor.responder(200, """
                [{"paymentId":"p-1","purchaseId":"compra-1","amount":46001.00,"status":"RECHAZADO","paymentMethod":"DIGITAL_WALLET"},
                 {"paymentId":"p-2","purchaseId":"compra-1","amount":46001.00,"status":"APROBADO","paymentMethod":"CREDIT_CARD"}]
                """);

        List<Pago> pagos = cliente.listarPagosPorCompra("compra-1");

        assertThat(pagos).extracting(Pago::paymentId, Pago::status)
                .containsExactly(tuple("p-1", EstadoPago.RECHAZADO), tuple("p-2", EstadoPago.APROBADO));
        assertThat(pagos.get(1).amount()).isEqualByComparingTo("46001.00");
    }

    @Test
    void unErrorDePagosEsUnaFallaDelServicioExterno() {
        servidor.responder(500, "{\"error\":\"ERROR_INTERNO\"}");

        assertThatThrownBy(() -> cliente.listarPagosPorCompra("compra-1"))
                .isInstanceOf(ServicioExternoException.class);
    }

    // ------------------------------------------------------------------ reintegros

    @Test
    void pideElReintegroPorLaRutaInternaConLaClaveYElCuerpoDelContrato() {
        servidor.responder(201, REINTEGRO);

        Reintegro r = cliente.reintegrar("p-1", "clave-reintegro-1", "pedido-1",
                new BigDecimal("10000.00"), "Devolución");

        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/pagos/p-1/reintegros");
        assertThat(idempotencyKey()).isEqualTo("clave-reintegro-1");
        assertThat(servidor.cuerpoRecibido).contains("\"refundRequestId\":\"pedido-1\"",
                "\"amount\":10000.00", "\"reason\":\"Devolución\"");
        assertThat(r.id()).isEqualTo("r-1");
        assertThat(r.status()).isEqualTo(EstadoReintegroPagos.EXITOSO);
    }

    @Test
    void reintentaElReintegroPorLaRutaInternaSinCuerpoNiClave() {
        servidor.responder(200, REINTEGRO);

        Reintegro r = cliente.reintentarReintegro("r-1");

        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/reintegros/r-1/reintentar");
        assertThat(idempotencyKey()).isNull();
        assertThat(servidor.cuerpoRecibido).isEmpty();
        assertThat(r.status()).isEqualTo(EstadoReintegroPagos.EXITOSO);
    }

    @Test
    void unConflictoAlReintentarEsUnConflictoDePagos() {
        servidor.responder(409, "{\"error\":\"REINTEGRO_NO_FALLIDO\"}");

        assertThatThrownBy(() -> cliente.reintentarReintegro("r-1"))
                .isInstanceOf(ConflictoEnPagosException.class);
    }

    @Test
    void consultaElReintegroPorLaRutaInterna() {
        servidor.responder(200, REINTEGRO);

        assertThat(cliente.consultarReintegro("r-1")).get()
                .extracting(Reintegro::status).isEqualTo(EstadoReintegroPagos.EXITOSO);
        assertThat(servidor.metodo).isEqualTo("GET");
        assertThat(servidor.ruta).isEqualTo("/internal/reintegros/r-1");
    }

    @Test
    void unReintegroInexistenteDaVacio() {
        servidor.responder(404, "{\"error\":\"REINTEGRO_NO_ENCONTRADO\"}");

        assertThat(cliente.consultarReintegro("r-1")).isEmpty();
    }

    // ------------------------------------------------------------------ rutas configuradas

    @Test
    void conRutasConfiguradasLlamaExactamenteAEsas() {
        PagosClienteRest viejas = clienteCon(new RutasPagos("/api/v1/payments",
                "/api/v1/payments/purchase/{id}", "/api/v1/payments/{id}/refunds",
                "/api/v1/refunds/{id}/retry", "/api/v1/refunds/{id}"));

        servidor.responder(201, PAGO);
        viejas.cobrar("clave-cobro-1", "compra-1", BigDecimal.TEN, "CREDIT_CARD");
        assertThat(servidor.ruta).isEqualTo("/api/v1/payments");

        servidor.responder(200, "[]");
        viejas.listarPagosPorCompra("compra-1");
        assertThat(servidor.ruta).isEqualTo("/api/v1/payments/purchase/compra-1");

        servidor.responder(201, REINTEGRO);
        viejas.reintegrar("p-1", "clave-reintegro-1", "pedido-1", BigDecimal.TEN, "Devolución");
        assertThat(servidor.ruta).isEqualTo("/api/v1/payments/p-1/refunds");

        servidor.responder(200, REINTEGRO);
        viejas.reintentarReintegro("r-1");
        assertThat(servidor.ruta).isEqualTo("/api/v1/refunds/r-1/retry");

        viejas.consultarReintegro("r-1");
        assertThat(servidor.ruta).isEqualTo("/api/v1/refunds/r-1");
    }
}
