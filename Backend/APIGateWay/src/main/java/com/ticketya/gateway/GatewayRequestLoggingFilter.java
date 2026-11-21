package com.ticketya.gateway;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.SignalType;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayRequestLoggingFilter implements WebFilter {

    /** Identificador de correlación acordado por el grupo para seguir un pedido entre servicios. */
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    /** Nombre anterior, que todavía usa Pagos. */
    static final String LEGACY_REQUEST_ID_HEADER = "X-Request-Id";
    static final String CORRELATION_ID_ATTRIBUTE = GatewayRequestLoggingFilter.class.getName() + ".correlationId";
    static final String USER_ID_ATTRIBUTE = GatewayRequestLoggingFilter.class.getName() + ".userId";
    static final String USER_ROLES_ATTRIBUTE = GatewayRequestLoggingFilter.class.getName() + ".userRoles";
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // X-Request-Id se acepta y reenvía con el mismo valor mientras Pagos migra a X-Correlation-Id.
        String correlationId = validCorrelationId(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER))
                .or(() -> validCorrelationId(exchange.getRequest().getHeaders().getFirst(LEGACY_REQUEST_ID_HEADER)))
                .orElseGet(() -> UUID.randomUUID().toString());
        exchange.getAttributes().put(CORRELATION_ID_ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        exchange.getResponse().getHeaders().set(LEGACY_REQUEST_ID_HEADER, correlationId);
        ServerWebExchange tracedExchange = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(CORRELATION_ID_HEADER, correlationId);
                    headers.set(LEGACY_REQUEST_ID_HEADER, correlationId);
                }))
                .build();
        long startNanos = System.nanoTime();
        return chain.filter(tracedExchange)
                .doFinally(signal -> logRequest(exchange, startNanos, signal == SignalType.ON_ERROR));
    }

    private void logRequest(ServerWebExchange exchange, long startNanos, boolean failed) {
        Object status = exchange.getResponse().getStatusCode() == null
                ? (failed ? HttpStatus.INTERNAL_SERVER_ERROR.value() : HttpStatus.OK.value())
                : exchange.getResponse().getStatusCode().value();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        LOGGER.info("http_request correlationId={} method={} path={} userId={} userRoles={} status={} durationMs={}",
                exchange.getAttribute(CORRELATION_ID_ATTRIBUTE),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                exchange.getAttributeOrDefault(USER_ID_ATTRIBUTE, "anonimo"),
                exchange.getAttributeOrDefault(USER_ROLES_ATTRIBUTE, "sin_rol"),
                status,
                elapsedMillis);
    }

    private static java.util.Optional<String> validCorrelationId(String value) {
        if (value == null || value.length() > 100 || !value.matches("[A-Za-z0-9._-]+")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value);
    }
}
