# personal-acceso Specification

## Purpose
Gestionar las asignaciones de personal de acceso por evento y permitir la consulta inmediata de su autorización para validar entradas.

## Requirements

### Requirement: Autorizar la gestión de personal del evento
MS1 SHALL permitir gestionar personal de acceso únicamente al organizador titular identificado por `X-User-Id` y el rol `ORGANIZADOR`, o a un usuario con rol `ADMIN` en `X-User-Roles`. SHALL evaluar la lista completa de roles y responder `403` cuando no se cumplan las condiciones de identidad y autorización. Un evento inexistente SHALL responder `404`.

#### Scenario: Organizador ajeno intenta gestionar personal
- **GIVEN** un evento existente y un organizador cuyo ID no es el del titular
- **WHEN** solicita asignar, listar o revocar personal del evento
- **THEN** responde `403 Forbidden` sin modificar asignaciones

#### Scenario: Administrador puede gestionar personal
- **GIVEN** un evento existente y una identidad cuya lista de roles incluye `ADMIN`
- **WHEN** gestiona el personal de ese evento
- **THEN** se permite la operación aunque no sea su organizador titular

#### Scenario: Evento inexistente
- **GIVEN** un evento inexistente y una identidad autorizada para solicitar la gestión
- **WHEN** intenta asignar, listar o revocar personal
- **THEN** responde `404 Not Found`

### Requirement: Asignar personal de acceso validado en MS6
`POST /eventos/{eventoId}/personal-acceso` SHALL recibir un correo válido, consultar `GET /internal/usuarios/por-correo?correo=...` en MS6 con `X-Service-Token` y `X-Correlation-Id`, y exigir `activo=true` y el rol `PERSONAL_ACCESO`. El `usuarioId` SHALL ser el UUID textual (`String`) devuelto en `id`. SHALL registrar evento, usuario, estado y fechas; responde `201 Created` con la asignación. Body inválido SHALL responder `400` sin escritura.

#### Scenario: Asignar correo válido con rol de acceso
- **GIVEN** un organizador titular o `ADMIN` y MS6 devuelve un usuario activo con rol `PERSONAL_ACCESO`
- **WHEN** envía un correo válido para un usuario aún no asignado
- **THEN** responde `201 Created` con la asignación `ACTIVA`, sus IDs y fechas

#### Scenario: Rechazar correo sin rol de acceso
- **GIVEN** MS6 encuentra el correo pero el usuario no tiene `PERSONAL_ACCESO`
- **WHEN** se solicita la asignación
- **THEN** responde `400 Bad Request` y no crea ni reactiva asignaciones

#### Scenario: Rechazar usuario inactivo
- **GIVEN** MS6 devuelve un usuario con `activo=false`
- **WHEN** se solicita la asignación
- **THEN** responde `400 Bad Request` sin crear ni reactivar asignaciones

#### Scenario: Rechazar correo inexistente
- **GIVEN** MS6 responde `404` al consultar el correo
- **WHEN** se solicita la asignación
- **THEN** responde `404 Not Found` sin registrar una asignación

#### Scenario: Rechazar body inválido
- **GIVEN** una solicitud de alta con correo ausente o inválido
- **WHEN** se solicita la asignación
- **THEN** responde `400 Bad Request` sin consultar MS6 ni modificar asignaciones

#### Scenario: MS6 no disponible
- **GIVEN** MS6 no responde dentro del timeout, ocurre un error de transporte o devuelve `5xx`
- **WHEN** se solicita una asignación
- **THEN** responde `503 Service Unavailable` sin crear ni reactivar asignaciones

#### Scenario: Error de configuración de autenticación con MS6
- **GIVEN** MS6 responde `401` o `403`
- **WHEN** se solicita una asignación
- **THEN** responde `503 Service Unavailable`, registra un error de configuración sin secretos y no escribe asignaciones

### Requirement: Interpretar el contrato de MS6
La respuesta `200` de MS6 SHALL contener `id` como UUID textual, `roles` como lista y `activo` como booleano; MS1 SHALL ignorar campos desconocidos. La consulta SHALL usar timeouts de conexión de 2 segundos y lectura de 5 segundos. Respuestas inesperadas o inválidas SHALL producir `503` sin escritura.

#### Scenario: Ignorar campos adicionales
- **GIVEN** MS6 responde con un usuario activo válido y campos adicionales
- **WHEN** se consulta el usuario para una asignación
- **THEN** MS1 ignora los campos adicionales y usa el ID, roles y estado recibidos

### Requirement: Evitar duplicados y permitir reactivación
MS1 SHALL mantener un índice único MongoDB sobre `(eventoId, usuarioId)` que impida más de una asignación por par en cualquier estado. Una asignación activa duplicada SHALL responder `409`; una revocada SHALL poder reactivarse sin crear otro documento.

#### Scenario: Rechazar duplicado activo
- **GIVEN** una asignación `ACTIVA` para el evento y el usuario hallado en MS6
- **WHEN** se solicita asignarlo nuevamente
- **THEN** responde `409 Conflict` sin modificar el registro

#### Scenario: Reactivar después de revocar
- **GIVEN** una asignación `REVOCADA` y MS6 confirma que el usuario tiene `PERSONAL_ACCESO`
- **WHEN** se solicita asignarlo nuevamente
- **THEN** responde `201 Created`, el mismo registro pasa a `ACTIVA`, actualiza la fecha de asignación y limpia la de revocación

#### Scenario: Alta concurrente del mismo usuario
- **GIVEN** solicitudes concurrentes para el mismo evento y usuario
- **WHEN** intentan crear o reactivar la asignación
- **THEN** como máximo una queda `ACTIVA`, se conserva un solo documento y la operación duplicada responde `409`

### Requirement: Listar y revocar personal de acceso
`GET /eventos/{eventoId}/personal-acceso` SHALL listar `usuarioId`, `estado`, `asignadaEn` y `revocadaEn`. `DELETE /eventos/{eventoId}/personal-acceso/{usuarioId}` SHALL conservar el documento como `REVOCADA` y SHALL ser idempotente.

#### Scenario: Listar asignaciones
- **GIVEN** un evento con asignaciones activas y revocadas
- **WHEN** su organizador titular o un `ADMIN` consulta el listado
- **THEN** responde `200 OK` con usuario, estado y fechas para cada asignación

#### Scenario: Revocar una asignación
- **GIVEN** una asignación `ACTIVA`
- **WHEN** el titular o `ADMIN` la revoca
- **THEN** responde `204 No Content`, conserva el registro como `REVOCADA` y registra la fecha

#### Scenario: Repetir revocación o revocar una asignación inexistente
- **GIVEN** una asignación ya revocada o un par evento/usuario sin asignación
- **WHEN** se solicita revocarla
- **THEN** responde `204 No Content` sin cambiar la fecha si ya estaba revocada, o `404 Not Found` si no existe

### Requirement: Consultar autorización actual de personal de acceso
`GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` SHALL no exigir headers de usuario ni exponerse por el Gateway. SHALL leer la asignación actual sin caché y responder `200 OK` con `eventoId`, `usuarioId` y `autorizado`; solo `ACTIVA` es `true`.

#### Scenario: Revocación con efecto inmediato
- **GIVEN** un usuario con asignación `ACTIVA` para un evento
- **WHEN** MS5 consulta la autorización, se revoca la asignación y vuelve a consultar
- **THEN** obtiene `autorizado: true` antes y `autorizado: false` después

#### Scenario: Usuario sin asignación
- **GIVEN** un par evento/usuario sin asignación, incluso si el evento no existe
- **WHEN** MS5 consulta la autorización
- **THEN** responde `200 OK` con `autorizado: false`
