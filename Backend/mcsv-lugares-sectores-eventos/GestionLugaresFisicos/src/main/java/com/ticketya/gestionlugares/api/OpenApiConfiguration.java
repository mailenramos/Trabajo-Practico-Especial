package com.ticketya.gestionlugares.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "TicketYa - Gestión de lugares, sectores y eventos",
        version = "1.0.0",
        description = "API para lugares y sectores, y para el ciclo de vida completo de eventos: creación en borrador, precios, límite por cliente, publicación con inicialización en Reservas, suspensión y reactivación de venta, cancelación y callback interno de disponibilidad."))
public class OpenApiConfiguration {
}
