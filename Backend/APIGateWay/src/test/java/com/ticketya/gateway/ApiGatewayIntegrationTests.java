package com.ticketya.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(OrderAnnotation.class)
class ApiGatewayIntegrationTests {

    private static final Backend MS1 = Backend.start();
    private static final Backend MS2 = Backend.start();
    private static final Backend MS3 = Backend.start();
    private static final Backend MS5 = Backend.start();

    @LocalServerPort
    private int gatewayPort;

    @Value("${gateway.cors.allowed-origins}")
    private String corsAllowedOrigins;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.ms1-url", MS1::url);
        registry.add("gateway.services.ms2-url", MS2::url);
        registry.add("gateway.services.ms3-url", MS3::url);
        registry.add("gateway.services.ms5-url", MS5::url);
    }

    @AfterAll
    static void stopBackends() {
        MS1.stop();
        MS2.stop();
        MS3.stop();
        MS5.stop();
    }

    @BeforeEach
    void clearCapturedRequests() {
        MS1.clear();
        MS2.clear();
        MS3.clear();
        MS5.clear();
    }

    @Test
    @Order(1)
    void forwardsEventSearchAndIdentityHeaders() throws Exception {
        HttpResponse<String> response = send("GET", "/eventos?q=rock&page=0", null,
                "X-User-Id", "cliente-1", "X-User-Role", "CLIENTE");

        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        CapturedRequest forwarded = MS1.take();
        assertEquals("GET", forwarded.method());
        assertEquals("/eventos?q=rock&page=0", forwarded.path());
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
        assertEquals("CLIENTE", forwarded.header("X-User-Role"));
        assertNull(MS2.poll());
        assertNull(MS3.poll());
        assertNull(MS5.poll());
    }

    @Test
    @Order(2)
    void forwardsReservationBodyAndUserId() throws Exception {
        String body = "{\"solicitudId\":\"request-1\",\"detalles\":[{\"sectorId\":\"sector-1\",\"fila\":1,\"numero\":3}]}";
        HttpResponse<String> response = send("POST", "/disponibilidad/eventos/evento-1/reservas", body,
                "Content-Type", "application/json", "X-User-Id", "cliente-1");

        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS2.take();
        assertEquals("POST", forwarded.method());
        assertEquals("/disponibilidad/eventos/evento-1/reservas", forwarded.path());
        assertEquals(body, forwarded.body());
        assertEquals("application/json", forwarded.header("Content-Type"));
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
    }

    @Test
    @Order(3)
    void forwardsPurchasePaymentAndIdempotencyKey() throws Exception {
        String body = "{\"medioDePago\":\"CREDIT_CARD\"}";
        HttpResponse<String> response = send("POST", "/compras/123e4567-e89b-12d3-a456-426614174000/pago", body,
                "Content-Type", "application/json", "X-User-Id", "cliente-1",
                "Idempotency-Key", "payment-request-1");

        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS3.take();
        assertEquals("POST", forwarded.method());
        assertEquals("/compras/123e4567-e89b-12d3-a456-426614174000/pago", forwarded.path());
        assertEquals(body, forwarded.body());
        assertEquals("payment-request-1", forwarded.header("Idempotency-Key"));
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
    }

    @Test
    @Order(4)
    void forwardsTicketQuery() throws Exception {
        HttpResponse<String> response = send("GET", "/entradas?estado=VIGENTE&page=1", null,
                "X-User-Id", "cliente-1");

        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS5.take();
        assertEquals("GET", forwarded.method());
        assertEquals("/entradas?estado=VIGENTE&page=1", forwarded.path());
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
    }

    @Test
    @Order(5)
    void doesNotInventIdentityHeaders() throws Exception {
        HttpResponse<String> response = send("GET", "/eventos", null);

        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS1.take();
        assertNull(forwarded.header("X-User-Id"));
        assertNull(forwarded.header("X-User-Role"));
    }

    @Test
    @Order(6)
    void routesEveryPublishedOperationToItsOwningService() throws Exception {
        java.util.List<RouteCase> routes = java.util.List.of(
                new RouteCase("GET", "/lugares", MS1),
                new RouteCase("POST", "/lugares", MS1),
                new RouteCase("GET", "/lugares/lugar-1", MS1),
                new RouteCase("GET", "/lugares/lugar-1/sectores", MS1),
                new RouteCase("POST", "/lugares/lugar-1/sectores", MS1),
                new RouteCase("GET", "/eventos", MS1),
                new RouteCase("POST", "/eventos", MS1),
                new RouteCase("GET", "/eventos/evento-1", MS1),
                new RouteCase("PUT", "/eventos/evento-1/sectores/sector-1/precio", MS1),
                new RouteCase("PUT", "/eventos/evento-1/limite-entradas-por-cliente", MS1),
                new RouteCase("PUT", "/eventos/evento-1/publicar", MS1),
                new RouteCase("PUT", "/eventos/evento-1/venta/suspender", MS1),
                new RouteCase("PUT", "/eventos/evento-1/venta/reactivar", MS1),
                new RouteCase("PUT", "/eventos/evento-1/cancelar", MS1),
                new RouteCase("POST", "/disponibilidad/eventos/evento-1/reservas", MS2),
                new RouteCase("GET", "/compras", MS3),
                new RouteCase("POST", "/compras", MS3),
                new RouteCase("GET", "/compras/compra-1", MS3),
                new RouteCase("POST", "/compras/compra-1/pago", MS3),
                new RouteCase("GET", "/devoluciones", MS3),
                new RouteCase("POST", "/devoluciones", MS3),
                new RouteCase("GET", "/devoluciones/devolucion-1", MS3),
                new RouteCase("POST", "/devoluciones/devolucion-1/aprobacion", MS3),
                new RouteCase("POST", "/devoluciones/devolucion-1/rechazo", MS3),
                new RouteCase("GET", "/reintegros", MS3),
                new RouteCase("POST", "/reintegros/reintegro-1/reintento", MS3),
                new RouteCase("GET", "/entradas", MS5),
                new RouteCase("GET", "/entradas/entrada-1", MS5));

        for (RouteCase route : routes) {
            MS1.clear();
            MS2.clear();
            MS3.clear();
            MS5.clear();

            HttpResponse<String> response = send(route.method(), route.path(),
                    "GET".equals(route.method()) ? null : "{}", "Content-Type", "application/json");

            assertEquals(200, response.statusCode(), route.method() + " " + route.path());
            assertEquals("{\"ok\":true}", response.body());
            CapturedRequest forwarded = route.backend().take();
            assertEquals(route.method(), forwarded.method());
            assertEquals(route.path(), forwarded.path());
            assertFalse(route.backend() != MS1 && MS1.poll() != null);
            assertFalse(route.backend() != MS2 && MS2.poll() != null);
            assertFalse(route.backend() != MS3 && MS3.poll() != null);
            assertFalse(route.backend() != MS5 && MS5.poll() != null);
        }
    }

    @Test
    @Order(7)
    void doesNotForwardInternalOrUnknownRoutes() throws Exception {
        assertNotPublished("GET", "/internal/eventos/evento-1", null);
        assertNotPublished("GET", "/disponibilidad/eventos/evento-1", null);
        assertNotPublished("GET", "/disponibilidad/reservas/reserva-1", null);
        assertNotPublished("PUT", "/eventos/evento-1/disponibilidad", "{}", "Content-Type", "application/json");
        assertNotPublished("POST", "/eventos-cancelados", "{}", "Content-Type", "application/json");
        assertNotPublished("POST", "/internal/entradas/emision", "{}", "Content-Type", "application/json");
        assertNotPublished("GET", "/internal/pagos/compra/1", null);
        assertNotPublished("DELETE", "/eventos", null);
        assertNotPublished("GET", "/not-a-public-route", null);

        assertNull(MS1.poll());
        assertNull(MS2.poll());
        assertNull(MS3.poll());
        assertNull(MS5.poll());
    }

    @Test
    @Order(8)
    void appliesCorsOnlyToConfiguredOrigin() throws Exception {
        HttpResponse<String> allowed = send("OPTIONS", "/eventos", null,
                "Origin", corsAllowedOrigins,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "content-type,x-user-id,idempotency-key");

        assertTrue(allowed.statusCode() < 400);
        assertEquals(corsAllowedOrigins, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .toLowerCase().contains("x-user-id"));

        HttpResponse<String> denied = send("OPTIONS", "/eventos", null,
                "Origin", "http://malicious.example",
                "Access-Control-Request-Method", "POST");
        assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    @Test
    @Order(9)
    void returnsServiceUnavailableWhenDestinationIsDown() throws Exception {
        MS1.stop();
        HttpResponse<String> response = send("GET", "/eventos", null);

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("SERVICIO_NO_DISPONIBLE"));
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        if ("GET".equals(method)) {
            request.GET();
        } else if ("OPTIONS".equals(method)) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertNotPublished(String method, String path, String body, String... headers) throws Exception {
        HttpResponse<String> response = send(method, path, body, headers);
        assertEquals(404, response.statusCode(), method + " " + path);
    }

    private record CapturedRequest(String method, String path, String body, java.util.Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private record RouteCase(String method, String path, Backend backend) {
    }

    private static final class Backend {
        private final HttpServer server;
        private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();

        private Backend(HttpServer server) {
            this.server = server;
        }

        static Backend start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                Backend backend = new Backend(server);
                server.createContext("/", backend::capture);
                server.start();
                return backend;
            } catch (IOException exception) {
                throw new IllegalStateException("No se pudo iniciar el backend falso", exception);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void capture(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(), values.get(0));
                }
            });
            requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    new String(body, StandardCharsets.UTF_8), headers));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        CapturedRequest take() throws InterruptedException {
            CapturedRequest request = requests.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(request, "El Gateway no reenvió la solicitud al backend esperado");
            return request;
        }

        CapturedRequest poll() {
            return requests.poll();
        }

        void clear() {
            requests.clear();
        }

        void stop() {
            server.stop(0);
        }
    }
}
