package com.ticketya.gestionlugares.filas.http;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "filas")
public class FilasProperties {
    private URI baseUrl = URI.create("http://localhost:8088");

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
}
