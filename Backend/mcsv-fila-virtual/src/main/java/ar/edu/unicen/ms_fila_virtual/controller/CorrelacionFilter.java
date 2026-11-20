package ar.edu.unicen.ms_fila_virtual.controller;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class CorrelacionFilter extends OncePerRequestFilter {
    public static final String CORRELACION = "correlationId";
    public static final String USUARIO = "usuarioId";
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelacionFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader("X-Correlation-Id");
        String id = recibido != null && recibido.matches("[A-Za-z0-9._:-]{1,128}")
                ? recibido : UUID.randomUUID().toString();
        long inicio = System.nanoTime();
        MDC.put(CORRELACION, id);
        response.setHeader("X-Correlation-Id", id);
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            LOGGER.error("Error inesperado durante solicitud HTTP: tipo={}", e.getClass().getName());
            throw e;
        } finally {
            try {
                Object patron = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                LOGGER.info("solicitud metodo={} operacion={} estado={} duracion_ms={} usuario={}",
                        request.getMethod(), patron == null ? "no-resuelta" : patron,
                        response.getStatus(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio),
                        MDC.get(USUARIO) == null ? "-" : MDC.get(USUARIO));
            } finally {
                MDC.remove(USUARIO);
                MDC.remove(CORRELACION);
            }
        }
    }
}
