package com.ticketya.usuarios.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI usuariosOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TicketYa MS6 - Usuarios y autenticación").version("1.0.0")
                        .description("Contrato de las rutas de usuario mediante el Gateway y de las rutas internas directas. El Gateway valida JWT y propaga la identidad; MS6 no valida JWT directamente. Las rutas internas exigen X-Service-Token cuando SERVICIO_TOKEN está configurado."))
                .servers(List.of(
                        new Server().url("http://localhost:8086").description("Gateway para las rutas de usuario"),
                        new Server().url("http://localhost:8087").description("MS6 directo en desarrollo o red interna")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("serviceToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Service-Token")
                                .description("Exigido en rutas internas cuando SERVICIO_TOKEN está configurado.")));
    }
}
