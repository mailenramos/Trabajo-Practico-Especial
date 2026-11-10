package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"PORT=8182", "spring.profiles.active=test"})
class ServerPortConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void variablePortSobrescribeElPuertoPredeterminado() {
        assertEquals("8182", environment.getProperty("server.port"));
    }
}
