package ticketya.cliente.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.cliente.PagosCliente.Pago;
import ticketya.cliente.ServicioExternoException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El cliente REST lee la consulta por compra como la responde Pagos: una lista. */
class PagosClienteRestTest {

    private HttpServer servidor;
    private final AtomicReference<String> ruta = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String cuerpo = "[]";
    private PagosClienteRest cliente;

    @BeforeEach
    void levantarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/", intercambio -> {
            ruta.set(intercambio.getRequestURI().getPath());
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(status, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        servidor.start();
        cliente = new PagosClienteRest("http://localhost:" + servidor.getAddress().getPort(),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void apagarServidor() {
        servidor.stop(0);
    }

    @Test
    void sinPagosDevuelveUnaListaVacia() {
        List<Pago> pagos = cliente.listarPagosPorCompra("compra-1");

        assertThat(pagos).isEmpty();
        assertThat(ruta.get()).isEqualTo("/api/v1/payments/purchase/compra-1");
    }

    @Test
    void conPagosLeeTodaLaListaConSusEstados() {
        cuerpo = """
                [{"paymentId":"p-1","purchaseId":"compra-1","amount":46001.00,"status":"RECHAZADO","paymentMethod":"DIGITAL_WALLET"},
                 {"paymentId":"p-2","purchaseId":"compra-1","amount":46001.00,"status":"APROBADO","paymentMethod":"CREDIT_CARD"}]
                """;

        List<Pago> pagos = cliente.listarPagosPorCompra("compra-1");

        assertThat(pagos).extracting(Pago::paymentId, Pago::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("p-1", EstadoPago.RECHAZADO),
                        org.assertj.core.groups.Tuple.tuple("p-2", EstadoPago.APROBADO));
        assertThat(pagos.get(1).amount()).isEqualByComparingTo("46001.00");
    }

    @Test
    void unErrorDePagosEsUnaFallaDelServicioExterno() {
        status = 500;
        cuerpo = "{\"error\":\"ERROR_INTERNO\"}";

        assertThatThrownBy(() -> cliente.listarPagosPorCompra("compra-1"))
                .isInstanceOf(ServicioExternoException.class);
    }
}
