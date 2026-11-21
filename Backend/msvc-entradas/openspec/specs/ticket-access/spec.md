# Especificación de validación de QR y control de acceso

## Purpose
Validar el código QR de una entrada en el ingreso de un evento, registrar el ingreso y restringir la operación al personal asignado al evento o a un administrador (US-5.1 a US-5.3 de `docs/historias-ms5-entrega2.md`).

## Status
- **Implementado y probado con tests unitarios y HTTP:** roles, consulta de asignación a MS1, rechazos por entrada inexistente, de otro evento, ya utilizada, no habilitada o evento cerrado, y mapeo de errores.
- **Implementado, con tests de integración con PostgreSQL (Testcontainers; requieren Docker), ejecutados sin fallos el 2026-11-20:** validaciones simultáneas del mismo QR.

## Requirements

### Requirement: Validar el QR y registrar el ingreso
`POST /eventos/{idEvento}/ingresos` con `{codigoQr}` MUST cambiar atómicamente la entrada de `VIGENTE` a `UTILIZADA` y registrar en `ingresos` la entrada, el evento, el usuario (`X-User-Id`), si fue excepcional y la fecha y hora UTC, en la misma transacción. MUST responder `201` con el sector, la fila y el número.

#### Scenario: Validación exitosa
- **WHEN** el personal autorizado valida el QR de una entrada `VIGENTE` del evento indicado
- **THEN** la entrada pasa a `UTILIZADA`, se registra el ingreso y la respuesta incluye la ubicación

#### Scenario: QR inexistente
- **WHEN** el código no corresponde a ninguna entrada
- **THEN** responde `404 ENTRADA_NO_ENCONTRADA`

#### Scenario: Entrada de otro evento
- **WHEN** la entrada pertenece a otro evento
- **THEN** responde `409 ENTRADA_DE_OTRO_EVENTO` y la entrada no cambia

#### Scenario: Entrada ya utilizada
- **WHEN** se valida una entrada `UTILIZADA`
- **THEN** responde `409 ENTRADA_YA_UTILIZADA` con la fecha del ingreso anterior

#### Scenario: Entrada no habilitada
- **WHEN** se valida una entrada `EN_REINTEGRO` o `DEVUELTA`
- **THEN** responde `409 ENTRADA_NO_HABILITADA` y la entrada no cambia

#### Scenario: Evento cerrado
- **WHEN** se valida una entrada de un evento cerrado
- **THEN** responde `409 EVENTO_CERRADO`

#### Scenario: Datos inválidos
- **WHEN** falta `codigoQr` o no es un UUID
- **THEN** responde `400 VALIDACION`

### Requirement: Impedir el doble uso de una entrada
De varias validaciones simultáneas del mismo QR, exactamente una MUST tener éxito y las demás MUST recibir `ENTRADA_YA_UTILIZADA`. `ingresos.id_entrada` MUST ser único.

#### Scenario: Escaneos simultáneos (reintento concurrente)
- **WHEN** ocho validaciones del mismo QR llegan a la vez
- **THEN** una responde `201`, siete responden `ENTRADA_YA_UTILIZADA` y existe un único ingreso

### Requirement: Autorizar solo al personal asignado al evento
La identidad MUST tomarse de `X-User-Id` y `X-User-Roles` (respaldo: `X-User-Role`). Sin `PERSONAL_ACCESO` ni `ADMIN` MUST responder `403 ACCESO_DENEGADO`. Con `PERSONAL_ACCESO` y sin `ADMIN`, MUST consultar en cada validación, sin caché, `GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` de MS1, enviando `X-Service-Token` (si está configurado) y `X-Correlation-Id`. `autorizado: false` MUST responder `403 PERSONAL_NO_AUTORIZADO`; un error, un 4xx/5xx o un timeout de MS1 MUST responder `503 AUTORIZACION_NO_DISPONIBLE`. Un `ADMIN` MUST poder validar sin asignación y el ingreso MUST quedar `excepcional`. La autorización MUST comprobarse antes de buscar la entrada.

#### Scenario: Personal sin asignación activa
- **WHEN** MS1 responde `autorizado: false`
- **THEN** responde `403 PERSONAL_NO_AUTORIZADO` sin consultar la entrada

#### Scenario: MS1 no disponible
- **WHEN** MS1 no responde o responde con error
- **THEN** la validación se rechaza temporalmente con `503 AUTORIZACION_NO_DISPONIBLE` y la entrada no cambia

#### Scenario: Validación excepcional de un administrador
- **WHEN** un `ADMIN` valida una entrada
- **THEN** no se consulta a MS1 y el ingreso queda con `excepcional: true`

## Technical Decisions and Constraints
- La consulta a MS1 se hace fuera de la transacción de base de datos, con timeouts configurables (`EVENTOS_CONNECT_TIMEOUT`, `EVENTOS_READ_TIMEOUT`; 1 s y 2 s por defecto).
- Una validación que ya obtuvo la autorización puede completarse aunque la asignación se revoque durante el procesamiento (sin coordinación distribuida).
- No hay ventana horaria: se valida mientras la entrada esté `VIGENTE` y el evento no esté cerrado.
- El QR es el UUID `codigo_qr`; el servicio no genera la imagen.

## Verification Evidence
- `IngresoServiceTest`, `EntradaControllerTest`, `AutorizacionAccesoClienteRestTest` (sin Docker).
- `IngresoConcurrencyIntegrationTest.validacionesSimultaneasDelMismoQr_soloUnaTieneExito` (Docker).
