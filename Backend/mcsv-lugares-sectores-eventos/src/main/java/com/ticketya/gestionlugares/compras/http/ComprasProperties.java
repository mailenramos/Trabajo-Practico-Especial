package com.ticketya.gestionlugares.compras.http;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "compras")
public class ComprasProperties {
    private URI baseUrl = URI.create("http://localhost:8083");

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
}
