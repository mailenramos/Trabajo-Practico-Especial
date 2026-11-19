package com.ticketya.preferencias.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ticketya.preferencias.integration.compras.ComprasProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AvisoTokenInterceptor implements HandlerInterceptor {
    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final ComprasProperties properties;

    public AvisoTokenInterceptor(ComprasProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String configuredToken = properties.getServicioToken();
        String receivedToken = request.getHeader(SERVICE_TOKEN_HEADER);
        if (configuredToken == null || configuredToken.isBlank() || receivedToken == null
                || !MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8),
                        receivedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ServicioTokenInvalidoException();
        }
        return true;
    }
}
