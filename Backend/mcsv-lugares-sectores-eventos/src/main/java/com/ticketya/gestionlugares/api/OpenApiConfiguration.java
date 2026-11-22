package com.ticketya.gestionlugares.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "El cliente envía Authorization: Bearer <jwt> al API Gateway. "
                + "El Gateway valida el JWT e inyecta X-User-Id (sub) y X-User-Roles; el cliente no envía esos headers. "
                + "MS1 no valida JWT y autoriza según los headers recibidos.")
@OpenAPIDefinition(info = @Info(
        title = "TicketYa - Gestión de lugares, sectores y eventos",
        version = "1.0.0",
        description = "API para lugares y sectores, y para el ciclo de vida completo de eventos: creación en borrador, precios, límite por cliente, publicación con inicialización en Reservas, suspensión y reactivación de venta, cancelación y callback interno de disponibilidad. "
                + "GET /eventos y GET /eventos/{eventoId} para eventos que no son borradores son públicos también a través del Gateway. "
                + "Las rutas internas y /actuator no se exponen por el Gateway."),
        servers = {
                @Server(url = "http://localhost:8086", description = "API Gateway"),
                @Server(url = "http://localhost:8081", description = "MS1 directo (desarrollo)")
        })
public class OpenApiConfiguration {
    @Bean
    OpenApiCustomizer headersDeIdentidadDelGateway() {
        return api -> api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getParameters() == null) return;
            operation.getParameters().forEach(parameter -> {
                if (!"header".equals(parameter.getIn())) return;
                String identity = switch (parameter.getName()) {
                    case "X-User-Id" -> "Identificador obtenido del claim sub del JWT.";
                    case "X-User-Roles" -> "Roles del JWT separados por comas.";
                    default -> null;
                };
                if (identity == null) return;
                String description = parameter.getDescription();
                parameter.setDescription(identity + " El Gateway inyecta este header; el cliente no lo envía. "
                        + "En acceso directo a MS1 puede ser necesario enviarlo."
                        + (description == null ? "" : " " + description));
                parameter.setRequired(false);
            });
        }));
    }
}
