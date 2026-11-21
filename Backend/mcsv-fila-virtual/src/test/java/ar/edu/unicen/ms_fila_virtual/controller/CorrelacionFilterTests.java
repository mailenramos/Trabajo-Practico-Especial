package ar.edu.unicen.ms_fila_virtual.controller;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import org.springframework.web.servlet.HandlerMapping;

class CorrelacionFilterTests {
    @Test
    void mantieneIdEnMdcYLoLimpiaAnteExcepcion() {
        var filtro = new CorrelacionFilter();
        var request = new MockHttpServletRequest("GET", "/prueba");
        request.addHeader("X-Correlation-Id", "corr-123");
        var response = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> filtro.doFilter(request, response, (req, res) -> {
            assertEquals("corr-123", MDC.get(CorrelacionFilter.CORRELACION));
            IdentidadActual.desde("usuario", "CLIENTE").exigirRol("CLIENTE");
            throw new ServletException("fallo controlado");
        }));
        assertEquals(500, response.getStatus());
        assertEquals("corr-123", response.getHeader("X-Correlation-Id"));
        assertNull(MDC.get(CorrelacionFilter.CORRELACION));
        assertNull(MDC.get(CorrelacionFilter.USUARIO));
    }

    @Test
    void registraIdentidadValidadaYNoFiltraSolicitudesConsecutivas() throws Exception {
        var eventos = new Captura();
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelacionFilter.class);
        eventos.start(); logger.addAppender(eventos);
        try {
            ejecutar("ana", "CLIENTE", "corr-ana", false);
            ejecutar(null, null, "corr-ausente", false);
            ejecutar(" ", "CLIENTE", "corr-invalida", false);
            ejecutar(null, null, "corr-interno", true);
            ejecutar("bea", "CLIENTE", "corr-error", false);
            var logs = eventos.eventos.stream().filter(e -> e.getFormattedMessage().startsWith("solicitud metodo=")).toList();
            assertEquals(5, logs.size());
            assertLog(logs.get(0), "corr-ana", "ana", "200");
            assertLog(logs.get(1), "corr-ausente", "-", "401");
            assertLog(logs.get(2), "corr-invalida", "-", "401");
            assertLog(logs.get(3), "corr-interno", "-", "204");
            assertLog(logs.get(4), "corr-error", "bea", "500");
            assertNull(MDC.get(CorrelacionFilter.USUARIO));
            assertNull(MDC.get(CorrelacionFilter.CORRELACION));
        } finally { logger.detachAppender(eventos); eventos.stop(); }
    }

    @Test
    void aislaUsuariosYCorrelacionesConcurrentes() throws Exception {
        var eventos = new Captura();
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelacionFilter.class);
        eventos.start(); logger.addAppender(eventos);
        var barrera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { ejecutarConcurrente("ana", "corr-a", barrera); return null; });
            var b = executor.submit(() -> { ejecutarConcurrente("bea", "corr-b", barrera); return null; });
            a.get(); b.get();
            var logs = eventos.eventos.stream().filter(e -> e.getFormattedMessage().startsWith("solicitud metodo=")).toList();
            assertEquals(2, logs.size());
            assertLog(logs.stream().filter(e -> "corr-a".equals(e.getMDCPropertyMap().get("correlationId"))).findFirst().orElseThrow(), "corr-a", "ana", "200");
            assertLog(logs.stream().filter(e -> "corr-b".equals(e.getMDCPropertyMap().get("correlationId"))).findFirst().orElseThrow(), "corr-b", "bea", "200");
        } finally { logger.detachAppender(eventos); eventos.stop(); }
    }

    private void ejecutar(String usuario, String roles, String correlacion, boolean interno) throws Exception {
        var req = new MockHttpServletRequest("GET", interno ? "/internal/test" : "/filas/test");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, interno ? "/internal/test" : "/filas/test");
        req.addHeader("X-Correlation-Id", correlacion);
        var resp = new MockHttpServletResponse();
        new CorrelacionFilter().doFilter(req, resp, (r, s) -> {
            if (interno) { resp.setStatus(204); return; }
            try { IdentidadActual.desde(usuario, roles).exigirRol("CLIENTE"); }
            catch (AccesoException e) { resp.setStatus(e.status().value()); return; }
            if ("corr-error".equals(correlacion)) resp.setStatus(500);
        });
        assertEquals(correlacion, resp.getHeader("X-Correlation-Id"));
    }

    private void ejecutarConcurrente(String usuario, String correlacion, CyclicBarrier barrera) throws Exception {
        var req = new MockHttpServletRequest("GET", "/filas/test");
        req.addHeader("X-Correlation-Id", correlacion);
        var resp = new MockHttpServletResponse();
        new CorrelacionFilter().doFilter(req, resp, (r, s) -> {
            IdentidadActual.desde(usuario, "CLIENTE").exigirRol("CLIENTE");
            try { barrera.await(); } catch (Exception e) { throw new ServletException(e); }
        });
        assertEquals(correlacion, resp.getHeader("X-Correlation-Id"));
        assertNull(MDC.get(CorrelacionFilter.USUARIO));
        assertNull(MDC.get(CorrelacionFilter.CORRELACION));
    }

    private void assertLog(ILoggingEvent evento, String correlacion, String usuario, String estado) {
        assertEquals(correlacion, evento.getMDCPropertyMap().get("correlationId"));
        String mensaje = evento.getFormattedMessage();
        assertTrue(mensaje.contains("metodo=GET"));
        assertTrue(mensaje.contains("operacion="));
        assertTrue(mensaje.contains("estado=" + estado));
        assertTrue(mensaje.contains("duracion_ms="));
        assertTrue(mensaje.contains("usuario=" + usuario));
    }

    private static class Captura extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> eventos = new CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            eventos.add(event);
        }
    }
}
