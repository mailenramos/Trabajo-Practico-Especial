package com.ticketya.msvc.entradas.seguridad;

import java.util.Set;

/**
 * Usuario que hace el pedido, tomado de X-User-Id y X-User-Roles. El API Gateway elimina esos
 * encabezados si los envía el cliente y los vuelve a escribir a partir del JWT validado.
 */
public record UsuarioActual(String id, Set<String> roles) {

    public static final String CLIENTE = "CLIENTE";
    public static final String ADMIN = "ADMIN";
    public static final String PERSONAL_ACCESO = "PERSONAL_ACCESO";

    public UsuarioActual {
        roles = Set.copyOf(roles);
    }

    public boolean tieneRol(String rol) {
        return roles.contains(rol);
    }

    public boolean esAdmin() {
        return tieneRol(ADMIN);
    }
}
