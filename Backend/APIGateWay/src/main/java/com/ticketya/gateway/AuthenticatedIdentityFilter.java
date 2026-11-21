package com.ticketya.gateway;

import java.util.List;
import java.util.Optional;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Propaga la identidad del JWT validado. Siempre elimina los encabezados de identidad que
 * mande el cliente (también en rutas públicas) para que nadie pueda suplantar usuarios.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticatedIdentityFilter implements GlobalFilter {

    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_ROLES_HEADER = "X-User-Roles";
    /**
     * Encabezado de la Entrega 1 con un único rol. Se mantiene solo para usuarios con un único rol,
     * mientras los servicios migran a X-User-Roles; nunca se elige un rol entre varios.
     */
    static final String LEGACY_USER_ROLE_HEADER = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> chain.filter(withIdentity(exchange, authentication)));
    }

    private static ServerWebExchange withIdentity(
            ServerWebExchange exchange, Optional<JwtAuthenticationToken> authentication) {
        Optional<String> userId = authentication.map(token -> token.getToken().getSubject());
        List<String> roles = authentication
                .map(token -> GatewaySecurityConfiguration.roles(token.getToken()))
                .orElse(List.of());
        String joinedRoles = String.join(",", roles);
        userId.ifPresent(id -> {
            exchange.getAttributes().put(GatewayRequestLoggingFilter.USER_ID_ATTRIBUTE, id);
            exchange.getAttributes().put(GatewayRequestLoggingFilter.USER_ROLES_ATTRIBUTE, joinedRoles);
        });
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLES_HEADER);
                    headers.remove(LEGACY_USER_ROLE_HEADER);
                    userId.ifPresent(id -> {
                        headers.set(USER_ID_HEADER, id);
                        headers.set(USER_ROLES_HEADER, joinedRoles);
                        if (roles.size() == 1) {
                            headers.set(LEGACY_USER_ROLE_HEADER, roles.get(0));
                        }
                    });
                }))
                .build();
    }
}
