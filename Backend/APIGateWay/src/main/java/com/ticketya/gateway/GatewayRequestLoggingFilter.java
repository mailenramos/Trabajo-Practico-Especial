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

    static final String REQUEST_ID_ATTRIBUTE = GatewayRequestLoggingFilter.class.getName() + ".requestId";
    static final String USER_ID_ATTRIBUTE = GatewayRequestLoggingFilter.class.getName() + ".userId";
    static final String USER_ROLE_ATTRIBUTE = GatewayRequestLoggingFilter.class.getName() + ".userRole";
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = validCorrelationId(exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"))
                .or(() -> validCorrelationId(exchange.getRequest().getHeaders().getFirst("X-Request-Id")))
                .orElseGet(() -> UUID.randomUUID().toString());
        String requestId = correlationId;
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set("X-Correlation-Id", correlationId);
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        ServerWebExchange tracedExchange = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set("X-Correlation-Id", correlationId);
                    headers.set("X-Request-Id", requestId);
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
        LOGGER.info("http_request requestId={} method={} path={} userId={} userRole={} status={} durationMs={}",
                exchange.getAttribute(REQUEST_ID_ATTRIBUTE),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                exchange.getAttributeOrDefault(USER_ID_ATTRIBUTE, "anonimo"),
                exchange.getAttributeOrDefault(USER_ROLE_ATTRIBUTE, "sin_rol"),
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
