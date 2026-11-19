package com.ticketya.preferencias.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.ticketya.preferencias.service.ForbiddenException;
import com.ticketya.preferencias.service.UnauthorizedException;

public final class UserIdentity {
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    public static final String USER_ID_ATTRIBUTE = "preferenciasUsuarioId";

    private UserIdentity() {
    }

    public static String requerirCliente(String userId, String rolesHeader) {
        if (userId == null || userId.isBlank() || rolesHeader == null) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Roles");
        }

        Set<String> roles = Arrays.stream(rolesHeader.split(",", -1))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!roles.contains("CLIENTE")) {
            throw new ForbiddenException("Se requiere el rol CLIENTE");
        }
        return userId.trim();
    }
}
