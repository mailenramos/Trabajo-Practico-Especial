# api-gateway Specification

## Purpose

Define el acceso local de la Entrega 1 a las APIs públicas de TicketYa a través de un API Gateway, sin autenticación real y sin exponer las rutas internas de comunicación entre servicios.

## ADDED Requirements

### Requirement: Enrutar operaciones públicas permitidas

El Gateway SHALL enrutar únicamente las operaciones públicas acordadas de Eventos/Lugares, Disponibilidad/Reservas, Compras/Devoluciones y Entradas al servicio correspondiente, conservando método, path, parámetros de consulta, cuerpo y headers requeridos por el contrato backend.

#### Scenario: Cliente consulta eventos a través del Gateway
- **WHEN** un cliente solicita una operación pública de eventos por el Gateway
- **THEN** el Gateway la reenvía a Eventos/Lugares con el mismo método, path y parámetros
- **AND** devuelve al cliente el status y cuerpo recibidos del servicio

#### Scenario: Cliente crea una reserva temporal
- **WHEN** un cliente envía `POST /disponibilidad/eventos/{eventoId}/reservas` por el Gateway
- **THEN** el Gateway reenvía el pedido a Disponibilidad/Reservas con el cuerpo y `X-User-Id`
- **AND** conserva la respuesta de creación o el error del servicio

#### Scenario: Cliente inicia y consulta una compra
- **WHEN** un cliente usa una ruta pública de Compras o Entradas por el Gateway
- **THEN** el Gateway reenvía la solicitud al servicio dueño de esa ruta sin modificar el contrato
- **AND** conserva headers de idempotencia y de identidad cuando corresponda

#### Scenario: Ruta pública no documentada
- **WHEN** se solicita un path o método que no está en la allowlist pública
- **THEN** el Gateway no reenvía la solicitud a ningún microservicio
- **AND** responde con un error HTTP de ruta o método no disponible

### Requirement: No exponer operaciones internas

El Gateway SHALL NOT enrutar callbacks, operaciones internas entre microservicios, ni endpoints del servicio de Pagos. La publicación SHALL usar una allowlist explícita de rutas y métodos.

#### Scenario: Intento de acceso a una ruta interna
- **WHEN** un cliente solicita una ruta bajo `/internal/**` o una ruta interna documentada con otro path
- **THEN** el Gateway no la reenvía al servicio destino
- **AND** responde con un error HTTP de ruta no disponible

#### Scenario: Callback de disponibilidad o cancelación
- **WHEN** un cliente solicita `PUT /eventos/{eventoId}/disponibilidad` o `POST /eventos-cancelados` por el Gateway
- **THEN** el Gateway no reenvía la solicitud

### Requirement: Propagar identidad de Entrega 1 sin autenticar

El Gateway SHALL conservar `X-User-Id` y `X-User-Role` recibidos en las solicitudes públicas cuando estén presentes. El Gateway SHALL NOT autenticar usuarios, crear una identidad por defecto ni interpretar esos encabezados como credenciales verificadas; cada servicio SHALL aplicar sus validaciones actuales.

#### Scenario: Solicitud con identidad de prueba
- **WHEN** una solicitud pública contiene `X-User-Id` y/o `X-User-Role`
- **THEN** el Gateway reenvía esos valores sin alterarlos

#### Scenario: Solicitud sin identidad requerida por el servicio
- **WHEN** una ruta pública se solicita sin el header requerido por su contrato
- **THEN** el Gateway reenvía la solicitud sin inventar una identidad
- **AND** la respuesta de validación del servicio se devuelve al cliente

### Requirement: Permitir consumo desde el frontend local

El Gateway SHALL aplicar CORS para orígenes configurados explícitamente, permitiendo los métodos y headers requeridos por las APIs públicas y las solicitudes de preflight. El Gateway SHALL NOT permitir todos los orígenes por defecto.

#### Scenario: Preflight desde origen configurado
- **WHEN** el navegador envía un preflight desde un origen permitido para un método y headers públicos admitidos
- **THEN** el Gateway responde con los headers CORS correspondientes

#### Scenario: Origen no configurado
- **WHEN** el navegador envía una solicitud desde un origen que no está configurado
- **THEN** el Gateway no autoriza ese origen mediante CORS

### Requirement: Configuración y fallo de servicio

El Gateway SHALL obtener el puerto de escucha, las URLs de los servicios destino y los orígenes CORS de configuración externa. Si un servicio destino no está disponible, SHALL responder con HTTP 503 y un error que indique indisponibilidad, sin reportar éxito.

#### Scenario: Destino configurado
- **WHEN** se despliega localmente el Gateway con las URLs de servicios configuradas
- **THEN** cada operación allowlisted se reenvía a la URL del servicio correspondiente

#### Scenario: Servicio destino no disponible
- **WHEN** el Gateway no puede conectar con el servicio dueño de una ruta pública
- **THEN** responde HTTP 503
- **AND** no devuelve una respuesta de éxito vacía o simulada
