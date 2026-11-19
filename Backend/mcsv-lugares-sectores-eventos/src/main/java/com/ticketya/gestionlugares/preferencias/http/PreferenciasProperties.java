package com.ticketya.gestionlugares.preferencias.http;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "preferencias")
public class PreferenciasProperties {
    private URI baseUrl = URI.create("http://localhost:8089");

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
}
