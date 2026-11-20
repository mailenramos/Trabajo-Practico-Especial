package ar.edu.unicen.ms_disponibilidad_reservas.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ClientesInternosTests {
    private HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String response;
    private volatile long delay;
    private Ms1EventoClient ms1;
    private Ms7FilaClient ms7;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // A timed-out client may have closed the connection.
            } finally {
                exchange.close();
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        ms1 = new Ms1EventoClient(url, 1000, 1000, mapper);
        ms7 = new Ms7FilaClient(url, 1000, 1000, mapper);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void erroresDeNegocioSeIgnoranYJsonInvalidoAbreCircuitosIndependientes() {
        response = "{}";
        Object http = org.springframework.test.util.ReflectionTestUtils.getField(ms1, "http");
        var circuito = (io.github.resilience4j.circuitbreaker.CircuitBreaker)
                org.springframework.test.util.ReflectionTestUtils.getField(http, "circuito");
        status = 404;
        for (int i = 0; i < 5; i++) assertEquals(IntegracionException.Tipo.NO_ENCONTRADO,
                assertThrows(IntegracionException.class, () -> ms1.consultar("e", null)).tipo());
        assertEquals(0, circuito.getMetrics().getNumberOfBufferedCalls());
        status = 400;
        assertEquals(IntegracionException.Tipo.RESPUESTA_INVALIDA,
                assertThrows(IntegracionException.class, () -> ms1.consultar("e", null)).tipo());
        assertEquals(0, circuito.getMetrics().getNumberOfBufferedCalls());
        status = 200;
        int antes = calls.get();
        for (int i = 0; i < 5; i++) assertEquals(IntegracionException.Tipo.RESPUESTA_INVALIDA,
                assertThrows(IntegracionException.class, () -> ms1.consultar("e", null)).tipo());
        assertEquals(antes + 5, calls.get());
        assertEquals(5, circuito.getMetrics().getNumberOfFailedCalls());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuito.getState());
        Object httpMs7 = org.springframework.test.util.ReflectionTestUtils.getField(ms7, "http");
        var circuitoMs7 = (io.github.resilience4j.circuitbreaker.CircuitBreaker)
                org.springframework.test.util.ReflectionTestUtils.getField(httpMs7, "circuito");
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuitoMs7.getState());
        status = 409;
        for (int i = 0; i < 5; i++) assertEquals(IntegracionException.Tipo.CONFLICTO,
                assertThrows(IntegracionException.class, () -> ms7.consumir("e", "u", "s", "h", null)).tipo());
        assertEquals(0, circuitoMs7.getMetrics().getNumberOfBufferedCalls());
        status = 200;
        response = valid().replace("\"autorizacionVigente\":true", "\"autorizacionVigente\":false");
        assertEquals(IntegracionException.Tipo.AUTORIZACION_NO_VIGENTE,
                assertThrows(IntegracionException.class, () -> ms7.consumir("ev 1", "u", " sol ", "h", null)).tipo());
        assertEquals(0, circuitoMs7.getMetrics().getNumberOfBufferedCalls());
        response = "{}";
        antes = calls.get();
        for (int i = 0; i < 5; i++) assertEquals(IntegracionException.Tipo.RESPUESTA_INVALIDA,
                assertThrows(IntegracionException.class, () -> ms7.consumir("e", "u", "s", "h", null)).tipo());
        assertEquals(antes + 5, calls.get());
        assertEquals(5, circuitoMs7.getMetrics().getNumberOfFailedCalls());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuitoMs7.getState());
        circuito.transitionToHalfOpenState();
        response = "{\"requiereFila\":false}";
        for (int i = 0; i < 10; i++) assertFalse(ms1.consultar("e", null).requiereFila());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuito.getState());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuitoMs7.getState());
        circuitoMs7.transitionToHalfOpenState();
        response = valid();
        for (int i = 0; i < 10; i++) assertTrue(ms7.consumir("ev 1", "u", " sol ", "h", null).autorizacionVigente());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuitoMs7.getState());
    }

    @Test
    void circuitoAbreNoReintentaPostYConsultaReintentaSoloFallosTransitorios() {
        response = "{}";
        status = 503;
        assertThrows(IntegracionException.class, () -> ms1.consultar("e", null));
        assertEquals(2, calls.get());
        calls.set(0);
        assertThrows(IntegracionException.class, () -> ms7.consumir("e", "u", "s", "h", null));
        assertEquals(1, calls.get());
        for (int i = 0; i < 4; i++) assertThrows(IntegracionException.class, () -> ms1.consultar("e", null));
        int antes = calls.get();
        assertThrows(IntegracionException.class, () -> ms1.consultar("e", null));
        assertEquals(antes, calls.get());
        Object http = org.springframework.test.util.ReflectionTestUtils.getField(ms1, "http");
        var circuito = (io.github.resilience4j.circuitbreaker.CircuitBreaker)
                org.springframework.test.util.ReflectionTestUtils.getField(http, "circuito");
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuito.getState());
        circuito.transitionToHalfOpenState();
        status = 200; response = "{\"requiereFila\":false}";
        for (int i = 0; i < 10; i++) assertFalse(ms1.consultar("e", null).requiereFila());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuito.getState());
    }

    @Test
    void ms1ReadsExplicitBooleanAndAllowsExtraFields() {
        response = "{\"requiereFila\":true,\"extra\":1}";
        assertTrue(ms1.consultar("evento 1", "corr-1").requiereFila());
        assertEquals("GET", method.get());
        assertEquals("/internal/eventos/evento 1", path.get());
        assertEquals("corr-1", correlation.get());
        response = "{\"requiereFila\":false}";
        assertFalse(ms1.consultar("evento", null).requiereFila());
        assertNull(correlation.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"requiereFila\":null}", "{\"requiereFila\":1}",
            "{\"requiereFila\":\"false\"}", "[]", "{mal"})
    void ms1RejectsMissingMalformedOrCoercedValues(String json) {
        response = json;
        assertEquals(IntegracionException.Tipo.RESPUESTA_INVALIDA,
                assertThrows(IntegracionException.class, () -> ms1.consultar("evento", null)).tipo());
    }

    @Test
    void ms7PreservesExactValuesAndValidatesResponse() {
        response = valid();
        var consumo = ms7.consumir("ev 1", " usuario ", " sol ", " huella ", "corr-2");
        assertEquals(42, consumo.turnoId());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), consumo.expiraEn());
        assertTrue(consumo.autorizacionVigente());
        assertEquals("POST", method.get());
        assertEquals("/internal/filas/eventos/ev 1/consumos", path.get());
        assertEquals("corr-2", correlation.get());
        var sent = mapper.readTree(request.get());
        assertEquals(" usuario ", sent.path("usuarioId").asText());
        assertEquals(" sol ", sent.path("solicitudId").asText());
        assertEquals(" huella ", sent.path("huellaSolicitud").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{mal", "[]",
            "{\"turnoId\":\"42\",\"eventoId\":\"ev 1\",\"solicitudId\":\" sol \",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"2030-01-01T00:00:00Z\",\"autorizacionVigente\":true}",
            "{\"turnoId\":42,\"eventoId\":\"otro\",\"solicitudId\":\" sol \",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"2030-01-01T00:00:00Z\",\"autorizacionVigente\":true}",
            "{\"turnoId\":42,\"eventoId\":\"ev 1\",\"solicitudId\":\"otra\",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"2030-01-01T00:00:00Z\",\"autorizacionVigente\":true}",
            "{\"turnoId\":42,\"eventoId\":\"ev 1\",\"solicitudId\":\" sol \",\"estado\":\"HABILITADO\",\"expiraEn\":\"2030-01-01T00:00:00Z\",\"autorizacionVigente\":true}",
            "{\"turnoId\":42,\"eventoId\":\"ev 1\",\"solicitudId\":\" sol \",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"invalida\",\"autorizacionVigente\":true}",
            "{\"turnoId\":42,\"eventoId\":\"ev 1\",\"solicitudId\":\" sol \",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"2030-01-01T00:00:00Z\",\"autorizacionVigente\":\"true\"}"})
    void ms7RejectsInvalidOrMismatchedResponses(String json) {
        response = json;
        assertEquals(IntegracionException.Tipo.RESPUESTA_INVALIDA,
                assertThrows(IntegracionException.class, () -> ms7.consumir("ev 1", "u", " sol ", "h", null)).tipo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"turnoId", "eventoId", "solicitudId", "estado", "expiraEn", "autorizacionVigente"})
    void ms7RejectsEveryMissingRequiredField(String field) {
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(valid());
        json.remove(field);
        response = mapper.writeValueAsString(json);
        assertEquals(IntegracionException.Tipo.RESPUESTA_INVALIDA,
                assertThrows(IntegracionException.class, () -> ms7.consumir("ev 1", "u", " sol ", "h", null)).tipo());
    }

    @Test
    void expiredAuthorizationIsNotACommunicationFailure() {
        response = valid().replace("true", "false");
        assertEquals(IntegracionException.Tipo.AUTORIZACION_NO_VIGENTE,
                assertThrows(IntegracionException.class, () -> ms7.consumir("ev 1", "u", " sol ", "h", null)).tipo());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 500})
    void remoteHttpErrorsRemainDistinct(int code) {
        status = code;
        response = "{}";
        var expected = code == 404 ? IntegracionException.Tipo.NO_ENCONTRADO
                : code == 409 ? IntegracionException.Tipo.CONFLICTO : IntegracionException.Tipo.NO_DISPONIBLE;
        assertEquals(expected, assertThrows(IntegracionException.class, () -> ms1.consultar("ev", null)).tipo());
        assertEquals(expected, assertThrows(IntegracionException.class, () -> ms7.consumir("ev", "u", "s", "h", null)).tipo());
    }

    @Test
    void timeoutsAreUnavailableAndNeverCauseAutomaticRetry() {
        delay = 500;
        response = valid();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var timed = new Ms7FilaClient(url, 1000, 50, mapper);
        assertEquals(IntegracionException.Tipo.NO_DISPONIBLE,
                assertThrows(IntegracionException.class, () -> timed.consumir("ev 1", "u", " sol ", "h", null)).tipo());
        assertEquals(1, calls.get());
    }

    @Test
    void ms1ReadTimeoutFailsClosed() {
        delay = 500;
        response = "{\"requiereFila\":false}";
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var timed = new Ms1EventoClient(url, 1000, 50, mapper);
        assertEquals(IntegracionException.Tipo.NO_DISPONIBLE,
                assertThrows(IntegracionException.class, () -> timed.consultar("ev", null)).tipo());
        assertEquals(1, calls.get());
    }

    @Test
    void connectionFailureIsUnavailableForBothServices() {
        server.stop(0);
        assertEquals(IntegracionException.Tipo.NO_DISPONIBLE,
                assertThrows(IntegracionException.class, () -> ms1.consultar("ev", null)).tipo());
        assertEquals(IntegracionException.Tipo.NO_DISPONIBLE,
                assertThrows(IntegracionException.class, () -> ms7.consumir("ev", "u", "s", "h", null)).tipo());
    }

    private String valid() {
        return "{\"turnoId\":42,\"eventoId\":\"ev 1\",\"solicitudId\":\" sol \",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"2030-01-01T00:00:00Z\",\"autorizacionVigente\":true,\"extra\":1}";
    }
}
