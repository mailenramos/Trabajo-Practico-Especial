package com.ticketya.msvc.entradas.seguridad;

import com.ticketya.msvc.entradas.exception.NoAutenticadoException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Arma el UsuarioActual. Lee los roles de X-User-Roles (lista separada por coma); si no viene,
 * usa X-User-Role de la Entrega 1, que el Gateway sigue enviando para usuarios con un único rol.
 */
@Component
public class UsuarioActualResolver implements HandlerMethodArgumentResolver {

    public static final String ENCABEZADO_ID = "X-User-Id";
    public static final String ENCABEZADO_ROLES = "X-User-Roles";
    public static final String ENCABEZADO_ROL_LEGADO = "X-User-Role";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return UsuarioActual.class.equals(parameter.getParameterType());
    }

    @Override
    public UsuarioActual resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                         NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String id = webRequest.getHeader(ENCABEZADO_ID);
        if (id == null || id.isBlank()) {
            throw new NoAutenticadoException("FALTA_IDENTIFICACION", "Falta el encabezado " + ENCABEZADO_ID);
        }
        String roles = webRequest.getHeader(ENCABEZADO_ROLES);
        if (roles == null || roles.isBlank()) {
            roles = webRequest.getHeader(ENCABEZADO_ROL_LEGADO);
        }
        return new UsuarioActual(id.trim(), roles(roles));
    }

    static Set<String> roles(String encabezado) {
        if (encabezado == null) {
            return Set.of();
        }
        return Arrays.stream(encabezado.split(","))
                .map(rol -> rol.trim().toUpperCase(Locale.ROOT))
                .filter(rol -> !rol.isEmpty())
                .collect(Collectors.toSet());
    }
}
