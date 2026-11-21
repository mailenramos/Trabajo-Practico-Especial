package ar.edu.unicen.ms_fila_virtual.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP server is a test double; MySQL must be a dedicated disposable instance. */
@EnabledIfEnvironmentVariable(named = "MS7_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class Ms1IntegracionMySqlTests {
    private static final AtomicReference<String> estado = new AtomicReference<>("publicado");
    private static final AtomicReference<String> organizador = new AtomicReference<>("org");
    private static final AtomicBoolean requiere = new AtomicBoolean(false);
    private static final AtomicInteger getStatus = new AtomicInteger(200);
    private static final AtomicInteger putStatus = new AtomicInteger(204);
    private static final AtomicLong demora = new AtomicLong(0);
    private static final AtomicInteger puts = new AtomicInteger(0);
    private static final AtomicReference<String> respuestaGet = new AtomicReference<>();
    private static volatile Runnable durantePut = () -> { };
    private static final HttpServer MS1 = iniciarServidor();

    private static HttpServer iniciarServidor() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/internal/eventos/e", exchange -> {
                try {
                    int status;
                    String respuesta = "";
                    if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/internal/eventos/e")) {
                        status = getStatus.get();
                        respuesta = "{\"eventoId\":\"e\",\"organizadorId\":\"" + organizador.get()
                                + "\",\"estado\":\"" + estado.get() + "\",\"requiereFila\":" + requiere.get() + ",\"nombre\":\"Evento\"}";
                        if (respuestaGet.get() != null) respuesta = respuestaGet.get();
                    } else if (exchange.getRequestMethod().equals("PUT") && exchange.getRequestURI().getPath().endsWith("/politica-fila")) {
                        puts.incrementAndGet();
                        durantePut.run();
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        status = putStatus.get();
                        if (status == 204) requiere.set(body.contains("true"));
                        long ms = demora.getAndSet(0);
                        if (ms > 0) try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    } else { status = 404; }
                    byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                    if (status != 204) exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // A client timeout may close the connection before the response is sent.
                } finally { exchange.close(); }
            });
            servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r); thread.setDaemon(true); return thread;
            }));
            servidor.start();
            return servidor;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void configuracion(DynamicPropertyRegistry registry) {
        String url = System.getenv("MS7_TEST_MYSQL_URL");
        String password = System.getenv("MS7_TEST_MYSQL_PASSWORD");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms7_disposable")
                || url.contains(":3307/") || password == null) throw new IllegalStateException("Se requiere MySQL descartable dedicado");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("ms1.api.base-url", () -> "http://127.0.0.1:" + MS1.getAddress().getPort());
        registry.add("ms1.api.read-timeout-ms", () -> "250");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM turnos");
        jdbc.update("DELETE FROM filas_evento");
        estado.set("publicado"); organizador.set("org"); requiere.set(false);
        getStatus.set(200); putStatus.set(204); demora.set(0); puts.set(0);
        respuestaGet.set(null); durantePut = () -> { };
    }

    @AfterAll static void cerrarServidor() { MS1.stop(0); }

    @Test
    void configuraActivaPausaReanudaYDesactivaRespetandoPropiedad() throws Exception {
        configurar("org", "ORGANIZADOR").andExpect(status().isNoContent());
        cambiar("org", "ORGANIZADOR", "ACTIVA").andExpect(status().isNoContent());
        assertTrue(requiere.get());
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "cliente").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isCreated());
        cambiar("org", "ORGANIZADOR", "PAUSADA").andExpect(status().isNoContent());
        assertTrue(requiere.get());
        assertEquals(1, puts.get());
        cambiar("org", "ORGANIZADOR", "ACTIVA").andExpect(status().isNoContent());
        cambiar("org", "ORGANIZADOR", "ACTIVA").andExpect(status().isNoContent());
        assertEquals(1, puts.get());
        cambiar("admin", "ADMIN", "DESACTIVADA").andExpect(status().isNoContent());
        assertFalse(requiere.get());
        assertEquals("VENCIDO", jdbc.queryForObject("SELECT estado FROM turnos WHERE usuario_id='cliente'", String.class));
        assertEquals(2, puts.get());
    }

    @Test
    void eventoSinFilaInformaQuePuedeReservarDirectamente() throws Exception {
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "cliente")
                .header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El evento no requiere fila virtual. Puede reservar directamente."));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos", Long.class));
    }

    @Test
    void verificacionDeExistenciaTitularidadEstadoYCasoDatosIncompletos() throws Exception {
        configurar("ajeno", "ORGANIZADOR").andExpect(status().isForbidden());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM filas_evento", Long.class));
        getStatus.set(404);
        mvc.perform(get("/filas/eventos/e").header("X-User-Id", "admin").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isNotFound());
        getStatus.set(200); estado.set("cancelado");
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isConflict());
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isConflict());
        estado.set("desconocido");
        configurar("admin", "ADMIN").andExpect(status().isServiceUnavailable());
        estado.set("publicado");
        respuestaGet.set("{\"eventoId\":\"e\",\"estado\":\"publicado\"}");
        configurar("org", "ORGANIZADOR").andExpect(status().isServiceUnavailable());
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, puts.get());
    }

    @Test
    void lasLlamadasRemotasNoRetienenBloqueosLocalesYRespetanElOrden() throws Exception {
        var observadas = new java.util.concurrent.CopyOnWriteArrayList<String>();
        durantePut = () -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT estado FROM filas_evento WHERE evento_id='e' FOR UPDATE NOWAIT");
                 var filas = statement.executeQuery()) {
                if (!filas.next()) throw new IllegalStateException("La fila debe existir antes del PUT");
                observadas.add(filas.getString(1));
            } finally { connection.rollback(); }
            return null;
        });
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isNoContent());
        cambiar("admin", "ADMIN", "DESACTIVADA").andExpect(status().isNoContent());
        assertEquals(java.util.List.of("DESACTIVADA", "DESACTIVADA"), observadas);
    }

    @Test
    void fallaDeMs1NuncaSeConfirmaYReintentoCompletaLaMismaTransicion() throws Exception {
        putStatus.set(503);
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isServiceUnavailable());
        assertEquals("DESACTIVADA", jdbc.queryForObject("SELECT estado FROM filas_evento WHERE evento_id='e'", String.class));
        putStatus.set(204);
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isNoContent());
        putStatus.set(503);
        cambiar("admin", "ADMIN", "DESACTIVADA").andExpect(status().isServiceUnavailable());
        assertEquals("DESACTIVADA", jdbc.queryForObject("SELECT estado FROM filas_evento WHERE evento_id='e'", String.class));
        assertTrue(requiere.get());
        putStatus.set(204);
        cambiar("admin", "ADMIN", "DESACTIVADA").andExpect(status().isNoContent());
        assertFalse(requiere.get());
    }

    @Test
    void timeoutConResultadoRemotoInciertoSeRecuperaConReintentoIdentico() throws Exception {
        demora.set(800);
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isServiceUnavailable());
        assertTrue(requiere.get());
        assertEquals("DESACTIVADA", jdbc.queryForObject("SELECT estado FROM filas_evento WHERE evento_id='e'", String.class));
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isNoContent());
        assertEquals("ACTIVA", jdbc.queryForObject("SELECT estado FROM filas_evento WHERE evento_id='e'", String.class));
    }

    @Test
    void respuestaPerdidaDeDesactivacionSeReintentaSinRestaurarTurnos() throws Exception {
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isNoContent());
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isCreated());
        demora.set(800);
        cambiar("admin", "ADMIN", "DESACTIVADA").andExpect(status().isServiceUnavailable());
        assertFalse(requiere.get());
        assertEquals("DESACTIVADA", jdbc.queryForObject("SELECT estado FROM filas_evento WHERE evento_id='e'", String.class));
        assertEquals("VENCIDO", jdbc.queryForObject("SELECT estado FROM turnos WHERE usuario_id='u'", String.class));
        cambiar("admin", "ADMIN", "DESACTIVADA").andExpect(status().isNoContent());
        assertFalse(requiere.get());
    }

    @Test
    void jsonMalformadoDeMs1RechazaOperacionSinEfectosLocales() throws Exception {
        respuestaGet.set("{\"eventoId\":");
        configurar("admin", "ADMIN").andExpect(status().isServiceUnavailable());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM filas_evento", Long.class));
    }

    @Test
    void cancelacionDuranteActualizacionRemotaImpideActivacionLocal() throws Exception {
        durantePut = () -> {
            try { mvc.perform(put("/internal/filas/eventos/e/cancelacion").header("X-Service-Token", "token-prueba")).andExpect(status().isNoContent()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        };
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isConflict());
        assertEquals("DESACTIVADA", jdbc.queryForObject("SELECT estado FROM filas_evento WHERE evento_id='e'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT CAST(cancelado AS UNSIGNED) FROM filas_evento WHERE evento_id='e'", Integer.class));
    }

    @Test
    void cancelacionLocalImpideReactivacionAunqueMs1DevuelvaEstadoDesactualizado() throws Exception {
        mvc.perform(put("/internal/filas/eventos/e/cancelacion").header("X-Service-Token", "token-prueba")).andExpect(status().isNoContent());
        cambiar("admin", "ADMIN", "ACTIVA").andExpect(status().isConflict());
        assertEquals(0, puts.get());
    }

    private org.springframework.test.web.servlet.ResultActions configurar(String usuario, String rol) throws Exception {
        return mvc.perform(put("/filas/eventos/e/configuracion").header("X-User-Id", usuario).header("X-User-Roles", rol)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":10,\"duracionMinutos\":5}"));
    }
    private org.springframework.test.web.servlet.ResultActions cambiar(String usuario, String rol, String estado) throws Exception {
        return mvc.perform(put("/filas/eventos/e/estado").header("X-User-Id", usuario).header("X-User-Roles", rol)
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"" + estado + "\"}"));
    }
}
