package com.ticketya.gestionlugares.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "TicketYa - Gestión de lugares, sectores y eventos",
        version = "1.0.0",
        description = "API para lugares, sectores y gestión inicial del ciclo de vida de eventos."))
public class OpenApiConfiguration {
}
