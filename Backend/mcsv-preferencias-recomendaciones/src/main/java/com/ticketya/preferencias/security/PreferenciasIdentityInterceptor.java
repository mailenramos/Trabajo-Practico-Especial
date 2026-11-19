package com.ticketya.preferencias.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

public class PreferenciasIdentityInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String usuarioId = UserIdentity.requerirCliente(
                request.getHeader(UserIdentity.USER_ID_HEADER),
                request.getHeader(UserIdentity.USER_ROLES_HEADER));
        request.setAttribute(UserIdentity.USER_ID_ATTRIBUTE, usuarioId);
        return true;
    }
}
