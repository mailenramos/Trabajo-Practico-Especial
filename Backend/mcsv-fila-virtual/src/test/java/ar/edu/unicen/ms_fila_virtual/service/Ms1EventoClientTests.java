package ar.edu.unicen.ms_fila_virtual.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpConnectTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class Ms1EventoClientTests {
    @Test
    void erroresDeNegocioSeIgnoranYRespuestaInvalidaCuentaComoFallo() throws Exception {
        var code = new java.util.concurrent.atomic.AtomicInteger(404);
        var servidor = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get(), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        servidor.start();
        try {
            var cliente = new Ms1EventoClient("http://127.0.0.1:" + servidor.getAddress().getPort(), 1000, 1000);
            var consulta = (io.github.resilience4j.circuitbreaker.CircuitBreaker)
                    org.springframework.test.util.ReflectionTestUtils.getField(cliente, "consulta");
            var politica = (io.github.resilience4j.circuitbreaker.CircuitBreaker)
                    org.springframework.test.util.ReflectionTestUtils.getField(cliente, "politica");
            for (int i = 0; i < 5; i++) assertThrows(FilaNoEncontradaException.class, () -> cliente.consultar("e"));
            assertEquals(0, consulta.getMetrics().getNumberOfBufferedCalls());
            code.set(409);
            for (int i = 0; i < 5; i++) assertThrows(FilaConflictException.class, () -> cliente.actualizarPolitica("e", true));
            assertEquals(0, politica.getMetrics().getNumberOfBufferedCalls());
            code.set(200);
            for (int i = 0; i < 5; i++) assertThrows(Ms1NoDisponibleException.class, () -> cliente.consultar("e"));
            assertEquals(5, consulta.getMetrics().getNumberOfFailedCalls());
            assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, consulta.getState());
        } finally { servidor.stop(0); }
    }

    @Test
    void circuitosIndependientesReintentanSoloGetYSeRecuperan() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var status = new java.util.concurrent.atomic.AtomicInteger(503);
        var servidor = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"eventoId\":\"e\",\"estado\":\"publicado\",\"requiereFila\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        servidor.start();
        try {
            var cliente = new Ms1EventoClient("http://127.0.0.1:" + servidor.getAddress().getPort(), 1000, 1000);
            assertThrows(Ms1NoDisponibleException.class, () -> cliente.consultar("e"));
            assertEquals(2, calls.get());
            for (int i = 0; i < 4; i++) assertThrows(Ms1NoDisponibleException.class, () -> cliente.consultar("e"));
            int antes = calls.get();
            assertThrows(Ms1NoDisponibleException.class, () -> cliente.consultar("e"));
            assertEquals(antes, calls.get());
            assertThrows(Ms1NoDisponibleException.class, () -> cliente.actualizarPolitica("e", true));
            assertEquals(antes + 1, calls.get());
            var cb = (io.github.resilience4j.circuitbreaker.CircuitBreaker)
                    org.springframework.test.util.ReflectionTestUtils.getField(cliente, "consulta");
            assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, cb.getState());
            cb.transitionToHalfOpenState(); status.set(200);
            for (int i = 0; i < 10; i++) assertNotNull(cliente.consultar("e"));
            assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, cb.getState());
        } finally { servidor.stop(0); }
    }

    @Test
    void propagaCorrelacionEnConsultaYPoliticaSinContaminarLaSiguienteLlamada() throws Exception {
        var recibidos = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var servidor = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", exchange -> {
            String id = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            recibidos.add(id == null ? "ausente" : id);
            byte[] body = exchange.getRequestMethod().equals("GET")
                    ? "{\"eventoId\":\"e\",\"estado\":\"publicado\",\"requiereFila\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : new byte[0];
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("GET") ? 200 : 204,
                    exchange.getRequestMethod().equals("GET") ? body.length : -1);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        servidor.start();
        try {
            var cliente = new Ms1EventoClient("http://127.0.0.1:" + servidor.getAddress().getPort(), 1000, 1000);
            org.slf4j.MDC.put("correlationId", "corr-ms1");
            cliente.consultar("e");
            cliente.actualizarPolitica("e", true);
            org.slf4j.MDC.remove("correlationId");
            cliente.consultar("e");
            assertEquals(java.util.List.of("corr-ms1", "corr-ms1", "ausente"), recibidos);
        } finally {
            org.slf4j.MDC.remove("correlationId");
            servidor.stop(0);
        }
    }

    @Test
    void timeoutDeConexionNoAutorizaLaOperacion() {
        RestClient http = mock(RestClient.class);
        var timeout = new HttpConnectTimeoutException("conexión agotada");
        when(http.get()).thenThrow(new ResourceAccessException("falló la conexión", timeout));
        var error = assertThrows(Ms1NoDisponibleException.class, () -> new Ms1EventoClient(http).consultar("e"));
        assertSame(timeout, error.getCause().getCause());
    }
}
