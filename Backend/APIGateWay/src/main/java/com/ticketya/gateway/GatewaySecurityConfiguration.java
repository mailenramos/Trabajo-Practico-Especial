package com.ticketya.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Configuration
public class GatewaySecurityConfiguration {

    private static final Set<String> ROLES = Set.of("CLIENTE", "ORGANIZADOR", "ADMIN", "PERSONAL_ACCESO");
    private static final String[] ALL_ROLES = {"CLIENTE", "ORGANIZADOR", "ADMIN", "PERSONAL_ACCESO"};
    /** Lista explícita de rutas públicas (US-12.1). */
    static final String[] PUBLIC_POST_ROUTES = {"/auth/registro", "/auth/login"};
    static final String[] PUBLIC_GET_ROUTES = {"/eventos", "/eventos/*"};

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${gateway.security.jwt-secret}") String secret,
            @Value("${gateway.security.jwt-issuer}") String issuer) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 bytes UTF-8");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("JWT_ISSUER no puede estar vacío");
        }

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Valida vigencia (exp/nbf) y que el emisor sea el servicio de usuarios (iss).
        OAuth2TokenValidator<Jwt> issuerAndTimestamps = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> validator = token -> {
            OAuth2TokenValidatorResult standardResult = issuerAndTimestamps.validate(token);
            if (standardResult.hasErrors()) {
                return standardResult;
            }
            List<String> roles = roles(token);
            if (token.getSubject() == null || token.getSubject().isBlank()
                    || token.getExpiresAt() == null
                    || roles.isEmpty() || !ROLES.containsAll(roles)) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "El token requiere sub, exp y roles válidos", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** Lista de roles del claim "roles"; vacía si falta o no es una lista de textos. */
    static List<String> roles(Jwt token) {
        try {
            List<String> roles = token.getClaimAsStringList("roles");
            return roles == null ? List.of() : roles.stream().distinct().sorted().toList();
        } catch (RuntimeException invalidClaim) {
            return List.of();
        }
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            ObjectMapper objectMapper) {
        Converter<Jwt, Mono<AbstractAuthenticationToken>> authenticationConverter = jwt -> Mono.just(
                new JwtAuthenticationToken(jwt,
                        roles(jwt).stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList(),
                        jwt.getSubject()));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Rutas públicas (sin token): registro, inicio de sesión y consulta pública de eventos.
                        .pathMatchers(HttpMethod.POST, PUBLIC_POST_ROUTES).permitAll()
                        .pathMatchers(HttpMethod.GET, PUBLIC_GET_ROUTES).permitAll()
                        // MS6 - Usuarios
                        .pathMatchers("/usuarios/me").hasAnyRole(ALL_ROLES)
                        .pathMatchers(HttpMethod.GET, "/usuarios", "/usuarios/*").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/usuarios/*/roles", "/usuarios/*/estado").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/lugares", "/lugares/**")
                        .hasAnyRole(ALL_ROLES)
                        .pathMatchers(HttpMethod.POST, "/eventos", "/lugares", "/lugares/*/sectores")
                        .hasAnyRole("ORGANIZADOR", "ADMIN")
                        .pathMatchers(HttpMethod.PUT,
                                "/eventos/*/sectores/*/precio",
                                "/eventos/*/limite-entradas-por-cliente",
                                "/eventos/*/publicar")
                        .hasAnyRole("ORGANIZADOR", "ADMIN")
                        .pathMatchers(HttpMethod.PUT,
                                "/eventos/*/venta/suspender",
                                "/eventos/*/venta/reactivar",
                                "/eventos/*/cancelar")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/disponibilidad/eventos/*/reservas", "/compras",
                                "/compras/*/pago", "/devoluciones")
                        .hasRole("CLIENTE")
                        .pathMatchers(HttpMethod.GET, "/compras", "/compras/*", "/devoluciones", "/devoluciones/*")
                        .hasAnyRole("CLIENTE", "ADMIN")
                        .pathMatchers(HttpMethod.POST,
                                "/devoluciones/*/aprobacion", "/devoluciones/*/rechazo")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/reintegros")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/reintegros/*/reintento")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/entradas", "/entradas/*")
                        .hasAnyRole("CLIENTE", "ADMIN")
                        // MS5 - Validación de QR en el acceso (MS5 verifica además la asignación al evento)
                        .pathMatchers(HttpMethod.POST, "/eventos/*/ingresos")
                        .hasAnyRole("PERSONAL_ACCESO", "ADMIN")
                        // Cualquier otra ruta no está enrutada: el Gateway responde 404 (incluidas /internal/**).
                        .anyExchange().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint((exchange, exception) ->
                                exception instanceof OAuth2AuthenticationException
                                        ? writeError(exchange, objectMapper, HttpStatus.UNAUTHORIZED,
                                                "TOKEN_INVALIDO",
                                                "El token es inválido, está vencido o no fue emitido por TicketYa")
                                        : writeError(exchange, objectMapper, HttpStatus.UNAUTHORIZED,
                                                "NO_AUTENTICADO", "Se requiere un token JWT válido"))
                        .accessDeniedHandler((exchange, exception) ->
                                writeError(exchange, objectMapper, HttpStatus.FORBIDDEN,
                                        "ACCESO_DENEGADO", "El rol no está autorizado para esta operación")))
                .build();
    }

    private static Mono<Void> writeError(
            ServerWebExchange exchange,
            ObjectMapper objectMapper,
            HttpStatus status,
            String error,
            String message) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of(
                    "timestamp", Instant.now().toString(),
                    "status", status.value(),
                    "error", error,
                    "mensaje", message));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }
}
