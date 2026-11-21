# api-gateway Specification

## Purpose
Define el acceso a las APIs públicas de TicketYa a través de un API Gateway, validando JWT y roles, registrando la trazabilidad HTTP y sin exponer las rutas internas de comunicación entre servicios.

## Requirements

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

### Requirement: Autenticar y autorizar solicitudes públicas con JWT

El Gateway SHALL validar JWT HS256 firmados con una clave externa de al menos 32 bytes UTF-8, exigir `sub`, `roles` y `exp`, y permitir únicamente los roles `CLIENTE`, `ORGANIZADOR`, `ADMIN` y `PERSONAL_ACCESO`. `roles` puede ser una lista o un string separado por comas; el claim singular `role` se admite durante la transición. Las rutas públicas SHALL aplicar autorización por rol; `X-User-Id`, `X-User-Roles` y `X-User-Role` SHALL derivarse de los claims validados y los valores entrantes SHALL descartarse.

#### Scenario: Solicitud sin token válido
- **WHEN** se solicita una ruta pública sin bearer token, con firma incorrecta, vencido o con claims/rol inválidos
- **THEN** el Gateway responde HTTP 401 y no llama al servicio destino

#### Scenario: Rol sin permiso
- **WHEN** un token válido solicita una operación que no está autorizada para su rol
- **THEN** el Gateway responde HTTP 403 y no llama al servicio destino

#### Scenario: Identidad enviada por el cliente
- **WHEN** una solicitud autenticada contiene `X-User-Id` o `X-User-Role` distintos a los claims del token
- **THEN** el Gateway reemplaza ambos headers por `sub` y `role` validados antes del reenvío

### Requirement: Permitir consumo desde el frontend local

El Gateway SHALL aplicar CORS para orígenes configurados explícitamente, permitiendo los métodos y headers requeridos por las APIs públicas, incluyendo `Authorization` y `X-Request-Id`, y las solicitudes de preflight. El Gateway SHALL NOT permitir todos los orígenes por defecto.

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

### Requirement: Enrutar actualización y asignación de personal de acceso de MS1

El Gateway SHALL enrutar `PUT /eventos/{eventoId}`, `POST /eventos/{eventoId}/personal-acceso`, `GET /eventos/{eventoId}/personal-acceso` y `DELETE /eventos/{eventoId}/personal-acceso/{usuarioId}` a MS1 conservando método, path, query, cuerpo y headers. Las cuatro operaciones SHALL requerir JWT; las de personal de acceso SHALL admitir organizador o administrador.

#### Scenario: Modificar evento
- **WHEN** se solicita `PUT /eventos/{eventoId}` con una identidad autorizada
- **THEN** el Gateway reenvía la solicitud a MS1 sin modificar su contrato

#### Scenario: Gestionar personal de acceso
- **WHEN** un organizador o administrador asigna, lista o revoca personal de acceso
- **THEN** el Gateway enruta el método y path exactos a MS1
- **AND** envía `X-User-Id` desde `sub` y todos los roles en `X-User-Roles` separados por comas

#### Scenario: Ruta interna o de operación de MS1
- **WHEN** se solicita `/internal/**`, `/actuator/**` o `PUT /eventos/{eventoId}/disponibilidad`
- **THEN** el Gateway no reenvía la solicitud a ningún servicio

### Requirement: Trazar solicitudes y limitar espera de servicios

El Gateway SHALL registrar método, path, usuario autenticado cuando exista, roles, status y duración de cada solicitud. SHALL conservar un `X-Correlation-Id` válido de hasta 100 caracteres con caracteres alfanuméricos, punto, guion o guion bajo, o generar un identificador nuevo; devolverlo al cliente y propagarlo al destino. `X-Request-Id` puede mantenerse como alias de compatibilidad. SHALL aplicar timeout configurable de conexión y respuesta, mapear desconexión/timeout a HTTP 503, y SHALL NOT reintentar automáticamente escrituras.

#### Scenario: Solicitud procesada
- **WHEN** una solicitud llega al Gateway
- **THEN** queda un log HTTP con método, path, status, duración y `X-Request-Id`
- **AND** la respuesta y el destino reciben el mismo `X-Correlation-Id`

#### Scenario: Destino excede el timeout
- **WHEN** el servicio destino no responde dentro del plazo configurado
- **THEN** el Gateway responde HTTP 503 sin simular éxito

#### Scenario: Ruta interna solicitada
- **WHEN** una solicitud no autenticada apunta a una ruta interna no publicada
- **THEN** el Gateway responde 404 sin reenviarla
