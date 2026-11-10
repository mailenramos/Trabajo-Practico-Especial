package com.ticketya.gestionlugares.compras.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class ComprasPropertiesTest {

    @Test
    void tieneDefaultsConfirmadosYPermiteSobrescribirlos() {
        ComprasProperties properties = new ComprasProperties();
        assertEquals(URI.create("http://localhost:8083"), properties.getBaseUrl());
        assertEquals(Duration.ofSeconds(2), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(5), properties.getReadTimeout());

        properties.setBaseUrl(URI.create("http://compras.local:8090"));
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(7));
        assertEquals(URI.create("http://compras.local:8090"), properties.getBaseUrl());
        assertEquals(Duration.ofSeconds(3), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(7), properties.getReadTimeout());
    }
}
