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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.jwt-secret=integration-test-secret-with-at-least-32-bytes")
@TestMethodOrder(OrderAnnotation.class)
class ApiGatewayIntegrationTests {

    private static final Backend MS1 = Backend.start();
    private static final Backend MS2 = Backend.start();
    private static final Backend MS3 = Backend.start();
    private static final Backend MS5 = Backend.start();
    private static final Backend MS6 = Backend.start();
    private static final Backend MS8 = Backend.start();
    private static final String SECRET = "integration-test-secret-with-at-least-32-bytes";
    private static final String ISSUER = "ticketya-usuarios";

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
        registry.add("gateway.services.ms6-url", MS6::url);
        registry.add("gateway.services.ms8-url", MS8::url);
    }

    @AfterAll
    static void stopBackends() {
        MS1.stop();
        MS2.stop();
        MS3.stop();
        MS5.stop();
        MS6.stop();
        MS8.stop();
    }

    @BeforeEach
    void clearCapturedRequests() {
        MS1.clear();
        MS2.clear();
        MS3.clear();
        MS5.clear();
        MS6.clear();
        MS8.clear();
    }

    @Test
    @Order(1)
    void forwardsEventSearchAndIdentityHeaders() throws Exception {
        String requestId = "45feec1c-77bb-4a01-9a10-0d19063a7772";
        HttpResponse<String> response = sendAs("GET", "/eventos?q=rock&page=0", null,
                "CLIENTE", "cliente-1", "X-Correlation-Id", requestId,
                "X-User-Id", "spoofed-user", "X-User-Role", "ADMIN");

        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        CapturedRequest forwarded = MS1.take();
        assertEquals(requestId, response.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals(requestId, forwarded.header("X-Correlation-Id"));
        assertEquals(requestId, forwarded.header("X-Request-Id"), "Compatibilidad con Pagos");
        assertEquals("GET", forwarded.method());
        assertEquals("/eventos?q=rock&page=0", forwarded.path());
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
        assertEquals("CLIENTE", forwarded.header("X-User-Roles"));
        assertEquals("CLIENTE", forwarded.header("X-User-Role"));
        assertNull(MS2.poll());
        assertNull(MS3.poll());
        assertNull(MS5.poll());
    }

    @Test
    @Order(2)
    void forwardsReservationBodyAndUserId() throws Exception {
        String body = "{\"solicitudId\":\"request-1\",\"detalles\":[{\"sectorId\":\"sector-1\",\"fila\":1,\"numero\":3}]}";
        HttpResponse<String> response = sendAs("POST", "/disponibilidad/eventos/evento-1/reservas", body,
                "CLIENTE", "cliente-1",
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
        HttpResponse<String> response = sendAs("POST", "/compras/123e4567-e89b-12d3-a456-426614174000/pago", body,
                "CLIENTE", "cliente-1",
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
        HttpResponse<String> response = sendAs("GET", "/entradas?estado=VIGENTE&page=1", null,
                "CLIENTE", "cliente-1",
                "X-User-Id", "cliente-1");

        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS5.take();
        assertEquals("GET", forwarded.method());
        assertEquals("/entradas?estado=VIGENTE&page=1", forwarded.path());
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
    }

    @Test
    @Order(5)
    void rejectsMissingTokenAndDoesNotCallBackend() throws Exception {
        HttpResponse<String> response = send("GET", "/entradas", null, "X-User-Id", "cliente-1");

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("NO_AUTENTICADO"));
        assertNull(MS5.poll());

        HttpResponse<String> profile = send("GET", "/usuarios/me", null);
        assertEquals(401, profile.statusCode());
        assertNull(MS6.poll());
        assertNull(MS8.poll());
    }

    @Test
    @Order(6)
    void rejectsWrongRoleAndUnknownRoleWithoutCallingBackend() throws Exception {
        HttpResponse<String> forbidden = sendAs("POST", "/eventos", "{}", "CLIENTE", "cliente-1",
                "Content-Type", "application/json");
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.body().contains("ACCESO_DENEGADO"));

        HttpResponse<String> invalidRole = send("GET", "/eventos", null,
                "Authorization", "Bearer " + token("user-1", "VISITANTE"));
        assertEquals(401, invalidRole.statusCode());
        assertNull(MS1.poll());
    }

    @Test
    @Order(7)
    void routesEveryPublishedOperationToItsOwningService() throws Exception {
        java.util.List<RouteCase> routes = java.util.List.of(
                new RouteCase("GET", "/lugares", MS1, "CLIENTE"),
                new RouteCase("POST", "/lugares", MS1, "ORGANIZADOR"),
                new RouteCase("GET", "/lugares/lugar-1", MS1, "CLIENTE"),
                new RouteCase("GET", "/lugares/lugar-1/sectores", MS1, "CLIENTE"),
                new RouteCase("POST", "/lugares/lugar-1/sectores", MS1, "ORGANIZADOR"),
                new RouteCase("GET", "/eventos", MS1, "CLIENTE"),
                new RouteCase("POST", "/eventos", MS1, "ORGANIZADOR"),
                new RouteCase("GET", "/eventos/evento-1", MS1, "CLIENTE"),
                new RouteCase("PUT", "/eventos/evento-1", MS1, "ORGANIZADOR"),
                new RouteCase("POST", "/eventos/evento-1/personal-acceso", MS1, "ORGANIZADOR"),
                new RouteCase("GET", "/eventos/evento-1/personal-acceso", MS1, "ADMIN"),
                new RouteCase("DELETE", "/eventos/evento-1/personal-acceso/usuario-1", MS1, "ADMIN"),
                new RouteCase("PUT", "/eventos/evento-1/sectores/sector-1/precio", MS1, "ORGANIZADOR"),
                new RouteCase("PUT", "/eventos/evento-1/limite-entradas-por-cliente", MS1, "ORGANIZADOR"),
                new RouteCase("PUT", "/eventos/evento-1/publicar", MS1, "ORGANIZADOR"),
                new RouteCase("PUT", "/eventos/evento-1/venta/suspender", MS1, "ADMIN"),
                new RouteCase("PUT", "/eventos/evento-1/venta/reactivar", MS1, "ADMIN"),
                new RouteCase("PUT", "/eventos/evento-1/cancelar", MS1, "ADMIN"),
                new RouteCase("POST", "/disponibilidad/eventos/evento-1/reservas", MS2, "CLIENTE"),
                new RouteCase("GET", "/compras", MS3, "CLIENTE"),
                new RouteCase("POST", "/compras", MS3, "CLIENTE"),
                new RouteCase("GET", "/compras/compra-1", MS3, "CLIENTE"),
                new RouteCase("POST", "/compras/compra-1/pago", MS3, "CLIENTE"),
                new RouteCase("GET", "/devoluciones", MS3, "CLIENTE"),
                new RouteCase("POST", "/devoluciones", MS3, "CLIENTE"),
                new RouteCase("GET", "/devoluciones/devolucion-1", MS3, "CLIENTE"),
                new RouteCase("POST", "/devoluciones/devolucion-1/aprobacion", MS3, "ADMIN"),
                new RouteCase("POST", "/devoluciones/devolucion-1/rechazo", MS3, "ADMIN"),
                new RouteCase("GET", "/reintegros", MS3, "ADMIN"),
                new RouteCase("POST", "/reintegros/reintegro-1/reintento", MS3, "ADMIN"),
                new RouteCase("GET", "/entradas", MS5, "CLIENTE"),
                new RouteCase("GET", "/entradas/entrada-1", MS5, "CLIENTE"),
                new RouteCase("POST", "/auth/registro", MS6, "CLIENTE"),
                new RouteCase("POST", "/auth/login", MS6, "CLIENTE"),
                new RouteCase("GET", "/usuarios/me", MS6, "PERSONAL_ACCESO"),
                new RouteCase("PUT", "/usuarios/me", MS6, "CLIENTE"),
                new RouteCase("GET", "/usuarios", MS6, "ADMIN"),
                new RouteCase("GET", "/usuarios/usuario-1", MS6, "ADMIN"),
                new RouteCase("PUT", "/usuarios/usuario-1/roles", MS6, "ADMIN"),
                new RouteCase("PUT", "/usuarios/usuario-1/estado", MS6, "ADMIN"),
                new RouteCase("GET", "/preferencias/me", MS8, "CLIENTE"),
                new RouteCase("PUT", "/preferencias/me", MS8, "CLIENTE"),
                new RouteCase("DELETE", "/preferencias/me", MS8, "CLIENTE"),
                new RouteCase("GET", "/recomendaciones/me", MS8, "CLIENTE"),
                new RouteCase("POST", "/eventos/evento-1/ingresos", MS5, "PERSONAL_ACCESO"),
                new RouteCase("POST", "/eventos/evento-1/ingresos", MS5, "ADMIN"));

        for (RouteCase route : routes) {
            MS1.clear();
            MS2.clear();
            MS3.clear();
            MS5.clear();
            MS6.clear();
            MS8.clear();

            HttpResponse<String> response = sendAs(route.method(), route.path(),
                    "GET".equals(route.method()) ? null : "{}", route.role(), "user-1",
                    "Content-Type", "application/json");

            assertEquals(200, response.statusCode(), route.method() + " " + route.path());
            assertEquals("{\"ok\":true}", response.body());
            CapturedRequest forwarded = route.backend().take();
            assertEquals(route.method(), forwarded.method());
            assertEquals(route.path(), forwarded.path());
            assertNull(route.backend().poll(), "El Gateway no debe reenviar la solicitud más de una vez");
            assertFalse(route.backend() != MS1 && MS1.poll() != null);
            assertFalse(route.backend() != MS2 && MS2.poll() != null);
            assertFalse(route.backend() != MS3 && MS3.poll() != null);
            assertFalse(route.backend() != MS5 && MS5.poll() != null);
            assertFalse(route.backend() != MS6 && MS6.poll() != null);
            assertFalse(route.backend() != MS8 && MS8.poll() != null);
        }
    }

    @Test
    @Order(8)
    void doesNotForwardInternalOrUnknownRoutes() throws Exception {
        assertNotPublished("GET", "/internal/eventos/evento-1", null);
        assertNotPublished("GET", "/disponibilidad/eventos/evento-1", null);
        assertNotPublished("GET", "/disponibilidad/reservas/reserva-1", null);
        assertNotPublished("POST", "/disponibilidad/reservas/reserva-1/cancelacion", "{}",
                "Content-Type", "application/json");
        assertNotPublished("POST", "/compras/compra-1/cancelacion", "{}",
                "Content-Type", "application/json");
        assertNotPublished("PUT", "/eventos/evento-1/disponibilidad", "{}", "Content-Type", "application/json");
        assertNotPublished("POST", "/eventos-cancelados", "{}", "Content-Type", "application/json");
        assertNotPublished("POST", "/internal/entradas/emision", "{}", "Content-Type", "application/json");
        assertNotPublished("GET", "/internal/pagos/compra/1", null);
        assertNotPublished("GET", "/actuator/health", null);
        assertNotPublished("DELETE", "/eventos", null);
        assertNotPublished("GET", "/not-a-public-route", null);
        assertNotPublished("GET", "/internal/usuarios/usuario-1/contacto", null);
        assertNotPublished("GET", "/internal/usuarios/por-correo?correo=a@b.test", null);
        assertNotPublished("DELETE", "/usuarios/usuario-1", null);

        assertNull(MS1.poll());
        assertNull(MS2.poll());
        assertNull(MS3.poll());
        assertNull(MS5.poll());
        assertNull(MS6.poll());
        assertNull(MS8.poll());
    }

    @Test
    @Order(9)
    void appliesCorsOnlyToConfiguredOrigin() throws Exception {
        HttpResponse<String> allowed = send("OPTIONS", "/eventos", null,
                "Origin", corsAllowedOrigins,
                "Access-Control-Request-Method", "DELETE",
                "Access-Control-Request-Headers", "authorization,x-correlation-id,idempotency-key");

        assertTrue(allowed.statusCode() < 400);
        assertEquals(corsAllowedOrigins, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .toLowerCase().contains("authorization"));

        HttpResponse<String> denied = send("OPTIONS", "/eventos", null,
                "Origin", "http://malicious.example",
                "Access-Control-Request-Method", "POST");
        assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    @Test
    @Order(10)
    void restrictsAccessStaffAssignmentToOrganizersAndAdmins() throws Exception {
        HttpResponse<String> forbidden = sendAs("GET", "/eventos/evento-1/personal-acceso", null,
                "CLIENTE", "cliente-1");
        assertEquals(403, forbidden.statusCode());
        HttpResponse<String> anonymous = send("GET", "/eventos/evento-1/personal-acceso", null);
        assertEquals(401, anonymous.statusCode(), "No debe caer en la ruta pública GET /eventos/*");
        assertNull(MS1.poll());

        HttpResponse<String> response = sendAs("POST", "/eventos/evento-1/personal-acceso", "{}",
                "organizador-1", new String[] {"CLIENTE", "ORGANIZADOR"}, "Content-Type", "application/json");
        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS1.take();
        assertEquals("organizador-1", forwarded.header("X-User-Id"));
        assertEquals("CLIENTE,ORGANIZADOR", forwarded.header("X-User-Roles"));
    }

    @Test
    @Order(16)
    void restrictsPreferenciasToClientesAndPropagatesIdentity() throws Exception {
        assertEquals(403, sendAs("GET", "/preferencias/me", null, "ORGANIZADOR", "org-1").statusCode());
        assertEquals(403, sendAs("GET", "/recomendaciones/me", null, "ADMIN", "admin-1").statusCode());
        assertEquals(401, send("GET", "/recomendaciones/me", null).statusCode());
        assertNull(MS8.poll());

        sendAs("GET", "/recomendaciones/me", null, "CLIENTE", "cliente-1", "X-User-Id", "spoofed");
        CapturedRequest forwarded = MS8.take();
        assertEquals("cliente-1", forwarded.header("X-User-Id"));
        assertEquals("CLIENTE", forwarded.header("X-User-Roles"));

        assertNotPublished("POST", "/internal/preferencias/eventos-publicados", "{}",
                "Content-Type", "application/json");
        assertNull(MS8.poll());
    }

    @Test
    @Order(17)
    void addsCorsHeadersToSecurityErrorsAndExposesCorrelationId() throws Exception {
        HttpResponse<String> unauthorized = send("GET", "/compras", null, "Origin", corsAllowedOrigins);
        assertEquals(401, unauthorized.statusCode());
        assertEquals(corsAllowedOrigins,
                unauthorized.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        HttpResponse<String> forbidden = sendAs("GET", "/reintegros", null, "CLIENTE", "cliente-1",
                "Origin", corsAllowedOrigins);
        assertEquals(403, forbidden.statusCode());
        assertEquals(corsAllowedOrigins,
                forbidden.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        HttpResponse<String> ok = send("GET", "/eventos", null, "Origin", corsAllowedOrigins);
        assertEquals(200, ok.statusCode());
        assertEquals(1, ok.headers().allValues("Access-Control-Allow-Origin").size());
        assertTrue(ok.headers().firstValue("Access-Control-Expose-Headers").orElse("")
                .toLowerCase().contains("x-correlation-id"));
    }

    @Test
    @Order(99) // Último: apaga el backend falso de MS1.
    void returnsServiceUnavailableWhenDestinationIsDown() throws Exception {
        MS1.stop();
        HttpResponse<String> response = sendAs("GET", "/eventos", null, "CLIENTE", "cliente-1");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("SERVICIO_NO_DISPONIBLE"));
    }

    @Test
    @Order(11)
    void servesPublicRoutesWithoutTokenAndStripsSpoofedIdentity() throws Exception {
        HttpResponse<String> events = send("GET", "/eventos?q=rock", null,
                "X-User-Id", "spoofed-user", "X-User-Roles", "ADMIN", "X-User-Role", "ADMIN");
        assertEquals(200, events.statusCode());
        CapturedRequest forwardedEvents = MS1.take();
        assertNull(forwardedEvents.header("X-User-Id"));
        assertNull(forwardedEvents.header("X-User-Roles"));
        assertNull(forwardedEvents.header("X-User-Role"));

        HttpResponse<String> login = send("POST", "/auth/login", "{}",
                "Content-Type", "application/json", "X-User-Id", "spoofed-user");
        assertEquals(200, login.statusCode());
        assertNull(MS6.take().header("X-User-Id"));
    }

    @Test
    @Order(12)
    void forwardsEveryRoleOfAMultiRoleUser() throws Exception {
        HttpResponse<String> response = sendAs("GET", "/entradas", null, "user-7",
                new String[] {"PERSONAL_ACCESO", "CLIENTE"}, "X-User-Roles", "ADMIN");

        assertEquals(200, response.statusCode());
        CapturedRequest forwarded = MS5.take();
        assertEquals("user-7", forwarded.header("X-User-Id"));
        assertEquals("CLIENTE,PERSONAL_ACCESO", forwarded.header("X-User-Roles"));
        assertNull(forwarded.header("X-User-Role"), "Con varios roles no se elige uno arbitrariamente");
    }

    @Test
    @Order(13)
    void rejectsExpiredForeignForgedAndMalformedTokens() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        List<String> invalidTokens = List.of(
                signedToken(Map.of("sub", "u", "iss", ISSUER, "roles", List.of("CLIENTE"), "exp", now - 120), SECRET),
                signedToken(Map.of("sub", "u", "iss", "otro-emisor", "roles", List.of("CLIENTE"), "exp", now + 3600),
                        SECRET),
                signedToken(Map.of("sub", "u", "iss", ISSUER, "roles", List.of("CLIENTE"), "exp", now + 3600),
                        "otro-secreto-que-tambien-tiene-32-bytes-o-mas"),
                signedToken(Map.of("sub", "u", "iss", ISSUER, "role", "CLIENTE", "exp", now + 3600), SECRET),
                "no-es-un-jwt");

        for (String invalid : invalidTokens) {
            HttpResponse<String> response = send("GET", "/entradas", null, "Authorization", "Bearer " + invalid);
            assertEquals(401, response.statusCode(), invalid);
            assertTrue(response.body().contains("TOKEN_INVALIDO"), response.body());
            assertTrue(response.body().contains("\"mensaje\""), response.body());
        }
        assertNull(MS5.poll());
    }

    @Test
    @Order(15)
    void restrictsTicketValidationAndQueriesByRole() throws Exception {
        HttpResponse<String> clienteValida = sendAs("POST", "/eventos/evento-1/ingresos", "{}", "CLIENTE", "u-1",
                "Content-Type", "application/json");
        assertEquals(403, clienteValida.statusCode());

        HttpResponse<String> personalConsulta = sendAs("GET", "/entradas", null, "PERSONAL_ACCESO", "u-2");
        assertEquals(403, personalConsulta.statusCode());

        HttpResponse<String> sinToken = send("POST", "/eventos/evento-1/ingresos", "{}",
                "Content-Type", "application/json");
        assertEquals(401, sinToken.statusCode());
        assertNull(MS5.poll());
        assertNull(MS1.poll());
    }

    @Test
    @Order(14)
    void restrictsUserAdministrationToAdmins() throws Exception {
        for (String[] request : new String[][] {
                {"GET", "/usuarios"}, {"GET", "/usuarios/usuario-1"},
                {"PUT", "/usuarios/usuario-1/roles"}, {"PUT", "/usuarios/usuario-1/estado"}}) {
            HttpResponse<String> response = sendAs(request[0], request[1],
                    "GET".equals(request[0]) ? null : "{}", "ORGANIZADOR", "user-1",
                    "Content-Type", "application/json");
            assertEquals(403, response.statusCode(), request[0] + " " + request[1]);
            assertTrue(response.body().contains("ACCESO_DENEGADO"));
        }
        assertNull(MS6.poll());
        assertNull(MS8.poll());
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

    private HttpResponse<String> sendAs(
            String method,
            String path,
            String body,
            String role,
            String userId,
            String... headers) throws Exception {
        List<String> allHeaders = new ArrayList<>();
        allHeaders.add("Authorization");
        allHeaders.add("Bearer " + token(userId, role));
        allHeaders.addAll(List.of(headers));
        return send(method, path, body, allHeaders.toArray(String[]::new));
    }

    private HttpResponse<String> sendAs(
            String method, String path, String body, String userId, String[] roles, String... headers)
            throws Exception {
        List<String> allHeaders = new ArrayList<>();
        allHeaders.add("Authorization");
        allHeaders.add("Bearer " + token(userId, roles));
        allHeaders.addAll(List.of(headers));
        return send(method, path, body, allHeaders.toArray(String[]::new));
    }

    private static String token(String userId, String... roles) {
        return signedToken(Map.of(
                "sub", userId,
                "iss", ISSUER,
                "roles", List.of(roles),
                "exp", System.currentTimeMillis() / 1000 + 3600), SECRET);
    }

    private static String signedToken(Map<String, Object> claims, String secret) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo crear el JWT de prueba", exception);
        }
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

    private record RouteCase(String method, String path, Backend backend, String role) {
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
