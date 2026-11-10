package ticketya.cliente.rest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Habilita las propiedades de los clientes REST (por ahora, las rutas de Pagos). */
@Configuration
@EnableConfigurationProperties(RutasPagos.class)
public class ClientesRestConfig {
}
