package com.example.msvc.pagos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "TicketYa - Servicio de Pagos",
        version = "1.0.0",
        description = "Servicio interno que simula la pasarela de pagos, consumido por Compras y Devoluciones. "
                + "No se expone por el API Gateway y no almacena datos reales de tarjetas."))
public class MsvcPagosApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsvcPagosApplication.class, args);
    }
}