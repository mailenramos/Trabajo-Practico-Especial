package com.example.msvc.pagos;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final java.util.regex.Pattern VALID_CORRELATION_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = validRequestId(request.getHeader("X-Request-Id"))
                .orElseGet(() -> UUID.randomUUID().toString());
        String correlationId = validCorrelationId(request.getHeader(CORRELATION_ID_HEADER))
                .orElse(requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        String previousRequestId = MDC.get(REQUEST_ID_MDC_KEY);
        String previousCorrelationId = MDC.get(CORRELATION_ID_MDC_KEY);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            LOGGER.info("http_request correlationId={} requestId={} method={} path={} status={} durationMs={}",
                    correlationId, requestId, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), elapsedMillis);
            restore(REQUEST_ID_MDC_KEY, previousRequestId);
            restore(CORRELATION_ID_MDC_KEY, previousCorrelationId);
        }
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    /** Acepta el X-Correlation-Id del Gateway/Compras solo con caracteres seguros para logs. */
    private static java.util.Optional<String> validCorrelationId(String value) {
        if (value == null) {
            return java.util.Optional.empty();
        }
        String trimmed = value.trim();
        return VALID_CORRELATION_ID.matcher(trimmed).matches()
                ? java.util.Optional.of(trimmed)
                : java.util.Optional.empty();
    }

    private static java.util.Optional<String> validRequestId(String value) {
        if (value == null) {
            return java.util.Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value)
                    ? java.util.Optional.of(value)
                    : java.util.Optional.empty();
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}
