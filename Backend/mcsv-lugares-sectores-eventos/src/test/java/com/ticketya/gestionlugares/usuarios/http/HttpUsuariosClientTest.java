package com.ticketya.gestionlugares.usuarios.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.ServiceUnavailableException;
import com.ticketya.gestionlugares.trazabilidad.CorrelacionHttpInterceptor;

@ExtendWith(OutputCaptureExtension.class)
class HttpUsuariosClientTest {
    private static final String UUID = "b06507ba-403b-4ce2-a828-72845ff212ad";

    @Test
    void consultaCodificadaConHeadersYDesconocidosIgnorados() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://usuarios.test")
                .requestInterceptor(new CorrelacionHttpInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpUsuariosClient client = cliente(builder);
        server.expect(requestTo("http://usuarios.test/internal/usuarios/por-correo?correo=nombre%2Btest%40ejemplo.com"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", "token-prueba"))
                .andExpect(header("X-Correlation-Id", "traza-1"))
                .andRespond(withSuccess("""
                        {"id":"%s","roles":["CLIENTE","PERSONAL_ACCESO"],"activo":true,"extra":"ignorado"}
                        """.formatted(UUID), MediaType.APPLICATION_JSON));
        MDC.put("correlationId", "traza-1");
        try {
            var usuario = client.consultarPorCorreo("nombre+test@ejemplo.com");
            assertEquals(UUID, usuario.id());
            assertEquals(List.of("CLIENTE", "PERSONAL_ACCESO"), usuario.roles());
            assertEquals(true, usuario.activo());
            server.verify();
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void traduceErroresSinFiltrarSecretos(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://usuarios.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpUsuariosClient client = cliente(builder);
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_REQUEST)) {
            server.expect(requestTo("http://usuarios.test/internal/usuarios/por-correo?correo=a%40b.com"))
                    .andRespond(withStatus(status).body("token-prueba secreto-cuerpo"));
        }
        server.expect(requestTo("http://usuarios.test/internal/usuarios/por-correo?correo=a%40b.com"))
                .andRespond(request -> { throw new IOException("token-prueba secreto-transporte"); });
        assertThrows(ResourceNotFoundException.class, () -> client.consultarPorCorreo("a@b.com"));
        for (int i = 0; i < 5; i++) {
            var error = assertThrows(ServiceUnavailableException.class, () -> client.consultarPorCorreo("a@b.com"));
            assertFalse(error.toString().contains("token-prueba"));
        }
        assertFalse(output.getAll().contains("token-prueba"));
        assertFalse(output.getAll().contains("secreto-cuerpo"));
        assertFalse(output.getAll().contains("secreto-transporte"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("Error de configuración de autenticación con MS6"));
        server.verify();
    }

    @Test
    void rechazaRespuestasIncompletasOInvalidas() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://usuarios.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpUsuariosClient client = cliente(builder);
        for (String body : List.of("{}", "{\"id\":\"no-es-uuid\",\"roles\":[],\"activo\":true}",
                "{\"id\":\"" + UUID + "\",\"roles\":[],\"activo\":null}", "no-json")) {
            server.expect(requestTo("http://usuarios.test/internal/usuarios/por-correo?correo=a%40b.com"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 4; i++) {
            assertThrows(ServiceUnavailableException.class, () -> client.consultarPorCorreo("a@b.com"));
        }
        server.verify();
    }

    @Test
    void configuracionRealPropagaHeaders() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var headers = new java.util.concurrent.LinkedBlockingQueue<List<String>>();
        server.createContext("/internal/usuarios/por-correo", exchange -> {
            headers.add(List.of(exchange.getRequestHeaders().getFirst("X-Service-Token"),
                    exchange.getRequestHeaders().getFirst("X-Correlation-Id")));
            byte[] body = ("{\"id\":\"" + UUID + "\",\"roles\":[\"PERSONAL_ACCESO\"],\"activo\":true}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            UsuariosProperties props = new UsuariosProperties();
            props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new HttpUsuariosClient(new UsuariosHttpConfiguration().usuariosRestClient(RestClient.builder(), props),
                    props, new ServicioToken("token-prueba", new MockEnvironment()));
            MDC.put("correlationId", "traza-real");
            client.consultarPorCorreo("a@b.com");
            assertEquals(List.of("token-prueba", "traza-real"), headers.take());
        } finally {
            MDC.remove("correlationId");
            server.stop(0);
        }
    }

    @Test
    void sinTokenOSinUrlNoHaceLaConsulta() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://usuarios.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UsuariosProperties props = new UsuariosProperties();
        props.setBaseUrl("http://usuarios.test");
        HttpUsuariosClient sinToken = new HttpUsuariosClient(builder.build(), props,
                new ServicioToken("", new MockEnvironment()));
        assertThrows(ServiceUnavailableException.class, () -> sinToken.consultarPorCorreo("a@b.com"));
        props.setBaseUrl("");
        HttpUsuariosClient sinUrl = new HttpUsuariosClient(builder.build(), props,
                new ServicioToken("token-prueba", new MockEnvironment()));
        assertThrows(ServiceUnavailableException.class, () -> sinUrl.consultarPorCorreo("a@b.com"));
        server.verify();
    }

    @Test
    void tiempoDeLecturaExcedidoSeTraduceA503() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/usuarios/por-correo", exchange -> {
            try {
                Thread.sleep(6500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            UsuariosProperties props = new UsuariosProperties();
            props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new HttpUsuariosClient(new UsuariosHttpConfiguration().usuariosRestClient(RestClient.builder(), props),
                    props, new ServicioToken("token-prueba", new MockEnvironment()));
            long inicio = System.nanoTime();
            assertThrows(ServiceUnavailableException.class, () -> client.consultarPorCorreo("a@b.com"));
            long duracion = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
            org.junit.jupiter.api.Assertions.assertTrue(duracion >= 4500 && duracion < 10000,
                    "Timeout de lectura inesperado: " + duracion + " ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noSigueRedireccionesNiReenviaTokenAOtroDestino() throws Exception {
        var destino = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var llamadas = new java.util.concurrent.atomic.AtomicInteger();
        destino.createContext("/", exchange -> {
            llamadas.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        destino.start();
        var ms6 = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        ms6.createContext("/internal/usuarios/por-correo", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + destino.getAddress().getPort() + "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        ms6.start();
        try {
            UsuariosProperties props = new UsuariosProperties();
            props.setBaseUrl("http://127.0.0.1:" + ms6.getAddress().getPort());
            var client = new HttpUsuariosClient(new UsuariosHttpConfiguration().usuariosRestClient(RestClient.builder(), props),
                    props, new ServicioToken("token-prueba", new MockEnvironment()));
            assertThrows(ServiceUnavailableException.class, () -> client.consultarPorCorreo("a@b.com"));
            assertEquals(0, llamadas.get());
        } finally {
            ms6.stop(0);
            destino.stop(0);
        }
    }

    private HttpUsuariosClient cliente(RestClient.Builder builder) {
        UsuariosProperties props = new UsuariosProperties();
        props.setBaseUrl("http://usuarios.test");
        return new HttpUsuariosClient(builder.build(), props, new ServicioToken("token-prueba", new MockEnvironment()));
    }
}
