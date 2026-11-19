package com.ticketya.gestionlugares.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ServicioToken {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServicioToken.class);
    private final String token;

    public ServicioToken(@Value("${servicio.token:}") String token, Environment environment) {
        this.token = token;
        if (!disponible()) {
            if (environment.acceptsProfiles(Profiles.of("docker"))) {
                throw new IllegalStateException("SERVICIO_TOKEN no configurada");
            }
            LOGGER.warn("SERVICIO_TOKEN no configurada; los avisos outbox esperan hasta configurarla");
        }
    }

    public boolean disponible() {
        return token != null && !token.isBlank();
    }

    public String valor() {
        if (!disponible()) throw new IllegalStateException("SERVICIO_TOKEN no configurada");
        return token;
    }

    public String ocultarEn(String mensaje) {
        return disponible() ? mensaje.replace(token, "[REDACTADO]") : mensaje;
    }
}
