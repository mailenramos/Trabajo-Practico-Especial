package com.ticketya.usuarios.web;

import com.ticketya.usuarios.modelo.Rol;

import java.util.Set;
import java.util.UUID;

/**
 * Usuario que hace el pedido, tomado de X-User-Id y X-User-Roles. El API Gateway elimina
 * esos encabezados si los manda el cliente y los vuelve a escribir a partir del JWT validado.
 */
public record UsuarioActual(UUID id, Set<Rol> roles) {

    public boolean esAdmin() {
        return roles.contains(Rol.ADMIN);
    }
}
