package com.ticketya.gestionlugares.trazabilidad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelacionFilterTest {
    private final CorrelacionFilter filtro = new CorrelacionFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(CorrelacionFilter.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void iniciar() {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void limpiar() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    @Test
    void propagaIdYRegistraSoloCamposPermitidos() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/eventos");
        request.setQueryString("password=secreto-query");
        request.addHeader(Correlacion.HEADER, "abc-123_X.y");
        request.addHeader("X-User-Id", "usuario\nforjado");
        request.addHeader("Authorization", "Bearer secreto-token");
        request.addHeader("X-Service-Token", "secreto-servicio");
        request.setContent("secreto-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("ajeno", "conservado");
        filtro.doFilter(request, response, (req, res) -> {
            assertEquals("abc-123_X.y", MDC.get(Correlacion.MDC_KEY));
            ((MockHttpServletResponse) res).setStatus(202);
        });
        assertEquals("abc-123_X.y", response.getHeader(Correlacion.HEADER));
        assertNull(MDC.get(Correlacion.MDC_KEY));
        assertEquals("conservado", MDC.get("ajeno"));
        assertEquals(1, logs.list.size());
        ILoggingEvent log = logs.list.getFirst();
        String mensaje = log.getFormattedMessage();
        assertTrue(mensaje.contains("metodo=POST ruta=/internal/eventos usuario=usuario_forjado estado=202 duracionMs="));
        assertEquals("abc-123_X.y", log.getMDCPropertyMap().get(Correlacion.MDC_KEY));
        for (String secreto : new String[] {"secreto-query", "secreto-token", "secreto-servicio", "secreto-body"}) {
            assertFalse(mensaje.contains(secreto));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "id con espacio", "id/otro", "id\nforjado", "á"})
    void generaUuidParaIdsInvalidos(String valor) throws Exception {
        comprobarGenerado(valor);
    }

    @Test
    void generaUuidParaAusenteYLargoExcesivoYAceptaLimite() throws Exception {
        comprobarGenerado(null);
        comprobarGenerado("a".repeat(101));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/eventos");
        request.addHeader(Correlacion.HEADER, "a".repeat(100));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, (req, res) -> { });
        assertEquals("a".repeat(100), response.getHeader(Correlacion.HEADER));
    }

    @Test
    void actuatorNoGeneraLogPeroConservaHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(Correlacion.HEADER, "health-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, (req, res) -> { });
        assertEquals("health-1", response.getHeader(Correlacion.HEADER));
        assertTrue(logs.list.isEmpty());
        assertNull(MDC.get(Correlacion.MDC_KEY));
    }

    @Test
    void excepcionRegistraUnaVezYNoFiltraContexto() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/eventos");
        request.addHeader(Correlacion.HEADER, "fallo-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(ServletException.class,
                () -> filtro.doFilter(request, response, (req, res) -> { throw new ServletException("secreto"); }));
        assertEquals("fallo-1", response.getHeader(Correlacion.HEADER));
        assertEquals(1, logs.list.size());
        assertTrue(logs.list.getFirst().getFormattedMessage().contains("estado=500"));
        assertFalse(logs.list.getFirst().getFormattedMessage().contains("secreto"));
        assertNull(MDC.get(Correlacion.MDC_KEY));
    }

    @Test
    void solicitudAsincronaRegistraAlFinalizarYLimpiaMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/eventos");
        request.setAsyncSupported(true);
        request.addHeader(Correlacion.HEADER, "async-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, (req, res) -> {
            ((MockHttpServletRequest) req).startAsync(req, res);
        });
        assertTrue(logs.list.isEmpty());
        assertNull(MDC.get(Correlacion.MDC_KEY));
        response.setStatus(204);
        request.getAsyncContext().complete();
        assertEquals(1, logs.list.size());
        assertTrue(logs.list.getFirst().getFormattedMessage().contains("estado=204"));
        assertEquals("async-1", logs.list.getFirst().getMDCPropertyMap().get(Correlacion.MDC_KEY));
        assertNull(MDC.get(Correlacion.MDC_KEY));
    }

    private void comprobarGenerado(String valor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/eventos");
        if (valor != null) request.addHeader(Correlacion.HEADER, valor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, (req, res) -> {
            assertNotNullUuid(MDC.get(Correlacion.MDC_KEY));
        });
        assertNotNullUuid(response.getHeader(Correlacion.HEADER));
        assertNull(MDC.get(Correlacion.MDC_KEY));
    }

    private static void assertNotNullUuid(String valor) {
        assertEquals(valor, UUID.fromString(valor).toString());
    }
}
