package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import java.util.Arrays;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class IdentidadReservaInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod metodo)
                || !metodo.getBeanType().equals(DisponibilidadController.class)
                || !metodo.getMethod().getName().equals("crearReserva")) return true;
        String usuario = request.getHeader("X-User-Id");
        if (usuario == null || usuario.isBlank() || usuario.codePointCount(0, usuario.length()) > 255
                || usuario.codePoints().anyMatch(Character::isISOControl)) {
            throw new AccesoException(HttpStatus.UNAUTHORIZED, "Se requiere una identidad válida.");
        }
        String roles = request.getHeader("X-User-Roles");
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim).noneMatch("CLIENTE"::equals)) {
            throw new AccesoException(HttpStatus.FORBIDDEN, "Se requiere el rol CLIENTE.");
        }
        MDC.put(CorrelacionFilter.USUARIO, usuario);
        return true;
    }
}
