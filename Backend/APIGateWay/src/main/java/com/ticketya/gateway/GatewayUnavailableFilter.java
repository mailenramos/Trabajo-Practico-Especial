package com.ticketya.gateway;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class GatewayUnavailableFilter implements GlobalFilter {

    private final ObjectMapper objectMapper;

    public GatewayUnavailableFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(error -> {
            if (!isConnectionFailure(error)) {
                return Mono.error(error);
            }
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(error);
            }

            byte[] body;
            try {
                body = objectMapper.writeValueAsBytes(Map.of(
                        "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "error", "SERVICIO_NO_DISPONIBLE",
                        "mensaje", "El servicio destino no está disponible"));
            } catch (JsonProcessingException serializationError) {
                return Mono.error(serializationError);
            }

            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
        });
    }

    private boolean isConnectionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientRequestException
                    || cause instanceof ConnectException
                    || cause instanceof TimeoutException
                    || cause instanceof IOException
                    || cause.getClass().getName().equals("io.netty.channel.ConnectTimeoutException")) {
                return true;
            }
        }
        return false;
    }
}
