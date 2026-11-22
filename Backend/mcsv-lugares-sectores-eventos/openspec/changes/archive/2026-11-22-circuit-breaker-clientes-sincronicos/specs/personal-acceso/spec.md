# Spec Delta

## MODIFIED Requirements

### Requirement: Asignar personal de acceso validado en MS6
`POST /eventos/{eventoId}/personal-acceso` SHALL recibir un correo válido, consultar `GET /internal/usuarios/por-correo?correo=...` en MS6 con `X-Service-Token` y `X-Correlation-Id`, salvo rechazo del circuito, y exigir `activo=true` y el rol `PERSONAL_ACCESO`. El `usuarioId` SHALL ser el UUID textual (`String`) devuelto en `id`. SHALL registrar evento, usuario, estado y fechas; responde `201 Created` con la asignación. Body inválido SHALL responder `400` sin escritura.

#### Scenario: Asignar correo válido con rol de acceso
- **GIVEN** un organizador titular o `ADMIN` y MS6 devuelve un usuario activo con rol `PERSONAL_ACCESO`
- **WHEN** envía un correo válido para un usuario aún no asignado
- **THEN** responde `201 Created` con la asignación `ACTIVA`, sus IDs y fechas

#### Scenario: Consultar MS6 con headers de servicio
- **GIVEN** una solicitud de asignación válida y el circuito de Usuarios permite la llamada
- **WHEN** MS1 consulta el correo
- **THEN** llama a `GET /internal/usuarios/por-correo?correo=...` con `X-Service-Token` y `X-Correlation-Id`

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

#### Scenario: Circuito de Usuarios abierto
- **GIVEN** el circuito de Usuarios está abierto y la solicitud es válida
- **WHEN** se solicita una asignación
- **THEN** responde `503 Service Unavailable` sin consultar MS6 ni crear o reactivar asignaciones

#### Scenario: Error de configuración de autenticación con MS6
- **GIVEN** MS6 responde `401` o `403`
- **WHEN** se solicita una asignación
- **THEN** responde `503 Service Unavailable`, registra un error de configuración sin secretos y no escribe asignaciones
