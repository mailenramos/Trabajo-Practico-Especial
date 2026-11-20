package com.ticketya.gestionlugares.usuarios.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usuarios")
public class UsuariosProperties {
    private String baseUrl = "http://localhost:8087";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
