package com.ticketya.usuarios.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Toma el X-Correlation-Id que propaga el Gateway (o genera uno) y lo agrega a cada línea de log. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Correlation-Id";
    public static final String CLAVE_MDC = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = valido(request.getHeader(ENCABEZADO));
        MDC.put(CLAVE_MDC, correlationId);
        response.setHeader(ENCABEZADO, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }

    private static String valido(String valor) {
        if (valor != null) {
            try {
                return UUID.fromString(valor).toString();
            } catch (IllegalArgumentException ignorado) {
                // Valor inválido: se genera uno nuevo.
            }
        }
        return UUID.randomUUID().toString();
    }
}
