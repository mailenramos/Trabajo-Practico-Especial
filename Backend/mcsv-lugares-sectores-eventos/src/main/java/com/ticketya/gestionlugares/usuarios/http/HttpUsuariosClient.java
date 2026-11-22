package com.ticketya.gestionlugares.usuarios.http;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.ServiceUnavailableException;

@Component
public class HttpUsuariosClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpUsuariosClient.class);
    private final RestClient client;
    private final UsuariosProperties properties;
    private final ServicioToken token;

    public HttpUsuariosClient(@Qualifier("usuariosRestClient") RestClient client,
            UsuariosProperties properties, ServicioToken token) {
        this.client = client;
        this.properties = properties;
        this.token = token;
    }

    public Usuario consultarPorCorreo(String correo) {
        if (!token.disponible() || properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            LOGGER.error("Configuración de MS6 incompleta: URL o token de servicio no configurados");
            throw new ConfiguracionException();
        }
        Usuario usuario;
        try {
            usuario = client.get().uri("/internal/usuarios/por-correo?correo={correo}", correo)
                    .header("X-Service-Token", token.valor())
                    .retrieve()
                    .onStatus(status -> status.value() != 200, (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new ResourceNotFoundException("Usuario no encontrado");
                        if (status == 401 || status == 403) {
                            LOGGER.error("Error de configuración de autenticación con MS6: HTTP {}", status);
                        } else {
                            LOGGER.warn("MS6 respondió HTTP inesperado: {}", status);
                        }
                        if (status >= 400 && status < 500) throw new Respuesta4xxException();
                        throw new ServiceUnavailableException("Servicio de usuarios no disponible");
                    }).body(Usuario.class);
        } catch (RestClientException exception) {
            LOGGER.warn("No se pudo consultar MS6 o su respuesta es inválida");
            throw new ServiceUnavailableException("Servicio de usuarios no disponible");
        }
        if (usuario == null || usuario.id() == null || usuario.roles() == null
                || usuario.roles().stream().anyMatch(role -> role == null)
                || usuario.activo() == null || !esUuid(usuario.id())) {
            LOGGER.warn("MS6 devolvió una respuesta inválida");
            throw new ServiceUnavailableException("Servicio de usuarios no disponible");
        }
        return usuario;
    }

    private static boolean esUuid(String valor) {
        try {
            return UUID.fromString(valor).toString().equalsIgnoreCase(valor);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usuario(String id, List<String> roles, Boolean activo) { }

    public static class Respuesta4xxException extends ServiceUnavailableException {
        public Respuesta4xxException() {
            super("Servicio de usuarios no disponible");
        }
    }

    public static class ConfiguracionException extends ServiceUnavailableException {
        public ConfiguracionException() {
            super("Servicio de usuarios no disponible");
        }
    }
}
