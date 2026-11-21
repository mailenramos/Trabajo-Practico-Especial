package com.ticketya.msvc.entradas.seguridad;

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

/**
 * Toma el X-Correlation-Id que propaga el Gateway o Compras (o genera uno si falta o no es un
 * UUID), lo deja en el MDC para los logs, lo devuelve en la respuesta y queda disponible para
 * reenviarlo en las llamadas a otros servicios.
 */
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

    /** Identificador de correlación del pedido en curso (o uno nuevo fuera de un pedido). */
    public static String actual() {
        String valor = MDC.get(CLAVE_MDC);
        return valor != null ? valor : UUID.randomUUID().toString();
    }

    private static String valido(String valor) {
        if (valor != null) {
            try {
                return UUID.fromString(valor.trim()).toString();
            } catch (IllegalArgumentException ignorado) {
                // Valor inválido: se genera uno nuevo.
            }
        }
        return UUID.randomUUID().toString();
    }
}
