package com.ticketya.preferencias;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;

@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(title = "TicketYa - Preferencias y recomendaciones", version = "1.0"),
        servers = {
                @Server(url = "http://localhost:8086", description = "API Gateway"),
                @Server(url = "http://localhost:8089", description = "MS8 directo (desarrollo)")
        })
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "El cliente envía Authorization: Bearer <jwt> al API Gateway, "
                + "que lo valida e inyecta X-User-Id y X-User-Roles; MS8 no valida JWT.")
public class PreferenciasApplication {

    public static void main(String[] args) {
        SpringApplication.run(PreferenciasApplication.class, args);
    }
}
