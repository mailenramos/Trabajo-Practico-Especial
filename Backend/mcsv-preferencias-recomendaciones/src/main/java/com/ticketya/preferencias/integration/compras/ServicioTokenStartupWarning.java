package com.ticketya.preferencias.integration.compras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ServicioTokenStartupWarning implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServicioTokenStartupWarning.class);

    private final ComprasProperties properties;

    public ServicioTokenStartupWarning(ComprasProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getServicioToken() == null || properties.getServicioToken().isBlank()) {
            LOGGER.warn("SERVICIO_TOKEN no está definida; las señales de Compras se omitirán si la integración falla.");
        }
    }
}
