package com.ticketya.gateway;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticatedIdentityFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> {
                    String userId = authentication.getToken().getSubject();
                    List<String> roles = GatewaySecurityConfiguration.rolesFrom(authentication.getToken());
                    String rolesHeader = String.join(",", roles);
                    exchange.getAttributes().put(GatewayRequestLoggingFilter.USER_ID_ATTRIBUTE, userId);
                    exchange.getAttributes().put(GatewayRequestLoggingFilter.USER_ROLE_ATTRIBUTE, rolesHeader);
                    ServerWebExchange authenticatedExchange = exchange.mutate()
                            .request(request -> request.headers(headers -> {
                                headers.remove("X-User-Id");
                                headers.remove("X-User-Role");
                                headers.remove("X-User-Roles");
                                headers.set("X-User-Id", userId);
                                headers.set("X-User-Roles", rolesHeader);
                                headers.set("X-User-Role", roles.get(0));
                            }))
                            .build();
                    return authenticatedExchange;
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }
}
