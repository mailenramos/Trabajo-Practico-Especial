package ar.edu.unicen.ms_fila_virtual.controller;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.slf4j.MDC;

record IdentidadActual(String usuarioId, Set<String> roles) {
    static IdentidadActual desde(String usuarioId, String roles) {
        if (usuarioId == null || usuarioId.isBlank() || usuarioId.codePointCount(0, usuarioId.length()) > 255
                || usuarioId.codePoints().anyMatch(Character::isISOControl)) {
            throw new AccesoException(HttpStatus.UNAUTHORIZED, "Se requiere una identidad válida.");
        }
        if (roles == null || roles.isBlank()) {
            throw new AccesoException(HttpStatus.FORBIDDEN, "Se requieren roles para esta operación.");
        }
        Set<String> valores = Arrays.stream(roles.split(",", -1)).map(String::trim).collect(Collectors.toSet());
        if (valores.contains("") || !Set.of("CLIENTE", "ORGANIZADOR", "ADMIN", "PERSONAL_ACCESO").containsAll(valores)) {
            throw new AccesoException(HttpStatus.FORBIDDEN, "Los roles proporcionados no son válidos.");
        }
        return new IdentidadActual(usuarioId, valores);
    }

    void exigirRol(String... permitidos) {
        if (Arrays.stream(permitidos).noneMatch(roles::contains)) {
            throw new AccesoException(HttpStatus.FORBIDDEN, "El usuario no tiene permisos para esta operación.");
        }
        MDC.put(CorrelacionFilter.USUARIO, usuarioId);
    }
}
