package com.ticketya.gestionlugares.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "JWT validado por el API Gateway.")
@OpenAPIDefinition(info = @Info(
        title = "TicketYa - Gestión de lugares, sectores y eventos",
        version = "1.0.0",
        description = "API para lugares y sectores, y para el ciclo de vida completo de eventos: creación en borrador, precios, límite por cliente, publicación con inicialización en Reservas, suspensión y reactivación de venta, cancelación y callback interno de disponibilidad."))
public class OpenApiConfiguration {
}
