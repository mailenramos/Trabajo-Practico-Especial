package com.ticketya.usuarios.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciales del administrador inicial. Se validan al arrancar sin escribir sus valores
 * en el log: si faltan, el servicio no inicia.
 */
@ConfigurationProperties(prefix = "usuarios.admin-inicial")
public record AdminInicialProperties(String email, String password, String nombre, String apellido) {

    public AdminInicialProperties {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Faltan las variables de entorno ADMIN_EMAIL y/o ADMIN_PASSWORD: "
                    + "son obligatorias para crear el administrador inicial");
        }
        nombre = nombre == null || nombre.isBlank() ? "Administrador" : nombre.trim();
        apellido = apellido == null || apellido.isBlank() ? "TicketYa" : apellido.trim();
    }

    @Override
    public String toString() {
        return "AdminInicialProperties[email=<oculto>, password=<oculto>]";
    }
}
