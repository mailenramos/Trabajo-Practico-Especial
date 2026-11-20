package com.ticketya.usuarios.web;

import com.ticketya.usuarios.error.ApiException;
import com.ticketya.usuarios.modelo.Rol;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class UsuarioActualResolver implements HandlerMethodArgumentResolver {

    public static final String ENCABEZADO_ID = "X-User-Id";
    public static final String ENCABEZADO_ROLES = "X-User-Roles";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return UsuarioActual.class.equals(parameter.getParameterType());
    }

    @Override
    public UsuarioActual resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                         NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String id = webRequest.getHeader(ENCABEZADO_ID);
        if (id == null || id.isBlank()) {
            throw ApiException.noAutenticado("Falta el encabezado " + ENCABEZADO_ID);
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.noAutenticado("El encabezado " + ENCABEZADO_ID + " no es un identificador válido");
        }
        return new UsuarioActual(uuid, roles(webRequest.getHeader(ENCABEZADO_ROLES)));
    }

    static Set<Rol> roles(String encabezado) {
        Set<Rol> roles = EnumSet.noneOf(Rol.class);
        if (encabezado != null) {
            Arrays.stream(encabezado.split(","))
                    .map(Rol::desde)
                    .flatMap(Optional::stream)
                    .forEach(roles::add);
        }
        return roles;
    }
}
