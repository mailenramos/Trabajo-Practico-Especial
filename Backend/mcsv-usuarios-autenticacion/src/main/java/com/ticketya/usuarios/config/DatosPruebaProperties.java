package com.ticketya.usuarios.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usuarios.datos-prueba")
public record DatosPruebaProperties(boolean habilitados) {
}
