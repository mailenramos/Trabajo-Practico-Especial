package com.ticketya.gestionlugares.compras.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ComprasPropertiesTest {

    @Test
    void usaUrlBasePredeterminadaYSobrescribeLaConfigurada() {
        ComprasProperties properties = new ComprasProperties();
        assertEquals(URI.create("http://localhost:8083"), properties.getBaseUrl());

        properties.setBaseUrl(URI.create("http://compras.local:8090"));
        assertEquals(URI.create("http://compras.local:8090"), properties.getBaseUrl());
    }
}
