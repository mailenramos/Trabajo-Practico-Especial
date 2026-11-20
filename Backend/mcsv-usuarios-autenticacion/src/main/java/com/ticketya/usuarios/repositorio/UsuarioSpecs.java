package com.ticketya.usuarios.repositorio;

import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

public final class UsuarioSpecs {

    private static final char ESCAPE = '\\';

    private UsuarioSpecs() {
    }

    public static Specification<Usuario> conRol(Rol rol) {
        return (root, query, cb) -> rol == null ? null : cb.isMember(rol, root.get("roles"));
    }

    /** Coincidencia parcial sobre el correo normalizado. */
    public static Specification<Usuario> correoContiene(String correo) {
        if (correo == null || correo.isBlank()) {
            return (root, query, cb) -> null;
        }
        String patron = "%" + escapar(correo.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(root.get("correo"), patron, ESCAPE);
    }

    private static String escapar(String texto) {
        String e = String.valueOf(ESCAPE);
        return texto.replace(e, e + e).replace("%", e + "%").replace("_", e + "_");
    }
}
