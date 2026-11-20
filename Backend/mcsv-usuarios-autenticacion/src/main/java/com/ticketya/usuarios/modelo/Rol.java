package com.ticketya.usuarios.modelo;

import java.util.Locale;
import java.util.Optional;

public enum Rol {
    CLIENTE,
    ORGANIZADOR,
    ADMIN,
    PERSONAL_ACCESO;

    /** Interpreta un nombre de rol sin distinguir mayúsculas ni espacios al borde. */
    public static Optional<Rol> desde(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        String normalizado = valor.trim().toUpperCase(Locale.ROOT);
        for (Rol rol : values()) {
            if (rol.name().equals(normalizado)) {
                return Optional.of(rol);
            }
        }
        return Optional.empty();
    }
}
