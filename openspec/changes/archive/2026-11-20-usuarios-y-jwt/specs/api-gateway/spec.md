# api-gateway Specification

## Purpose
Define el acceso a las APIs públicas de TicketYa a través de un API Gateway, validando JWT y roles, registrando la trazabilidad HTTP y sin exponer las rutas internas de comunicación entre servicios.

## Requirements

### Requirement: Enrutar operaciones públicas permitidas

El Gateway SHALL enrutar únicamente las operaciones públicas acordadas de Eventos/Lugares, Disponibilidad/Reservas, Compras/Devoluciones, Entradas y Usuarios (MS6) al servicio correspondiente, conservando método, path, parámetros de consulta, cuerpo y headers requeridos por el contrato backend.

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

#### Scenario: Usuario se registra, inicia sesión o administra usuarios
- **WHEN** se solicita `POST /auth/registro`, `POST /auth/login`, `GET|PUT /usuarios/me`, `GET /usuarios`, `GET /usuarios/{id}`, `PUT /usuarios/{id}/roles` o `PUT /usuarios/{id}/estado`
- **THEN** el Gateway reenvía la solicitud a Usuarios (MS6) sin modificar el contrato

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

(Entrega 2, US-12.1.) El Gateway SHALL validar JWT HS256 emitidos por Usuarios (MS6) y firmados con una clave externa de al menos 32 bytes UTF-8 compartida con ese servicio. SHALL verificar firma, vigencia (`exp`) y emisor (`iss` igual a `JWT_ISSUER`), y exigir `sub` y `roles`: una lista no vacía con valores entre `CLIENTE`, `ORGANIZADOR`, `ADMIN` y `PERSONAL_ACCESO`. Un usuario SHALL poder tener varios roles. Las rutas protegidas SHALL aplicar autorización por rol; `X-User-Id` (= `sub`) y `X-User-Roles` (roles separados por coma) SHALL derivarse de los claims validados, y los encabezados de identidad entrantes SHALL descartarse en todas las rutas, también en las públicas. Mientras los servicios migran, `X-User-Role` SHALL enviarse solo si el usuario tiene exactamente un rol. El Gateway SHALL mantener una lista explícita de rutas públicas: `POST /auth/registro`, `POST /auth/login`, `GET /eventos` y `GET /eventos/{id}`.

#### Scenario: Solicitud sin token
- **WHEN** se solicita una ruta protegida sin bearer token
- **THEN** el Gateway responde HTTP 401 con `error` `NO_AUTENTICADO` y no llama al servicio destino

#### Scenario: Token inválido
- **WHEN** se solicita una ruta con un token vencido, con firma incorrecta, de otro emisor, mal formado o sin `roles` válidos
- **THEN** el Gateway responde HTTP 401 con `error` `TOKEN_INVALIDO` en el formato de error estándar y no llama al servicio destino

#### Scenario: Rol sin permiso
- **WHEN** un token válido solicita una operación que no está autorizada para ninguno de sus roles
- **THEN** el Gateway responde HTTP 403 con `error` `ACCESO_DENEGADO` y no llama al servicio destino

#### Scenario: Identidad enviada por el cliente
- **WHEN** una solicitud contiene `X-User-Id`, `X-User-Roles` o `X-User-Role` enviados por el cliente
- **THEN** el Gateway los elimina y, si hay token válido, los reemplaza por los valores derivados del token antes del reenvío

#### Scenario: Usuario con varios roles
- **WHEN** un token válido trae más de un rol
- **THEN** el Gateway reenvía todos en `X-User-Roles` y no envía `X-User-Role`, para no elegir un rol arbitrariamente

#### Scenario: Ruta pública sin token
- **WHEN** se invoca registro, inicio de sesión o la consulta pública de eventos sin token
- **THEN** el Gateway reenvía la solicitud sin encabezados de identidad

### Requirement: Permitir consumo desde el frontend local

El Gateway SHALL aplicar CORS para orígenes configurados explícitamente, permitiendo los métodos (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`) y headers requeridos por las APIs públicas, incluyendo `Authorization` y `X-Request-Id`, y las solicitudes de preflight. El Gateway SHALL NOT permitir todos los orígenes por defecto.

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

### Requirement: Trazar solicitudes y limitar espera de servicios

El Gateway SHALL registrar método, path, usuario autenticado cuando exista, sus roles, status y duración de cada solicitud. SHALL aceptar o generar un UUID `X-Request-Id`, devolverlo al cliente y propagarlo al destino. SHALL aplicar timeout configurable de conexión y respuesta, mapear desconexión/timeout a HTTP 503, y SHALL NOT reintentar automáticamente escrituras.

#### Scenario: Solicitud procesada
- **WHEN** una solicitud llega al Gateway
- **THEN** queda un log HTTP con método, path, status, duración y `X-Request-Id`
- **AND** la respuesta y el destino reciben el mismo `X-Request-Id`

#### Scenario: Destino excede el timeout
- **WHEN** el servicio destino no responde dentro del plazo configurado
- **THEN** el Gateway responde HTTP 503 sin simular éxito

#### Scenario: Ruta interna solicitada
- **WHEN** una solicitud no autenticada apunta a una ruta interna no publicada
- **THEN** el Gateway responde 404 sin reenviarla
