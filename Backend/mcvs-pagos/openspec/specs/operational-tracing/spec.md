# operational-tracing Specification

## Purpose
Define el registro trazable de solicitudes y operaciones críticas, y el empaquetado del servicio de Pagos como contenedor ejecutado dentro de la red privada.

## Requirements

### Requirement: Correlacionar solicitudes HTTP

El servicio SHALL aceptar un `X-Request-Id` UUID válido o generar uno cuando falte o sea inválido, devolverlo en la respuesta y registrarlo junto con método, path, status y duración. El log SHALL omitir query strings, credenciales y claves de idempotencia.

#### Scenario: Solicitud con identificador válido
- **WHEN** Pagos recibe un UUID en `X-Request-Id`
- **THEN** conserva el identificador, lo devuelve y lo incluye en el log HTTP

#### Scenario: Solicitud sin identificador válido
- **WHEN** el header está ausente o no es un UUID
- **THEN** Pagos genera un UUID nuevo, lo devuelve y lo incluye en el log

### Requirement: Registrar operaciones financieras críticas

Pagos SHALL emitir logs estructurados para la creación/repetición de cobros, creación/repetición de reintegros y reintentos, incluyendo identificadores de operación y estado, sin datos de tarjeta ni claves idempotentes.

#### Scenario: Operación de pago resuelta
- **WHEN** se crea o repite una operación de pago
- **THEN** el log incluye los IDs de pago y compra, el estado, el indicador de repetición y el `X-Request-Id` si se recibió

#### Scenario: Reintegro procesado
- **WHEN** se crea o reintenta un reintegro
- **THEN** el log incluye IDs de reintegro, solicitud y pago, el estado y el contador de intentos

### Requirement: Desplegar Pagos en contenedor

El servicio SHALL contar con una imagen Docker Java 17 no-root, configurable por las variables de conexión existentes, y SHALL permanecer en una red privada sin publicarse a través del Gateway.

#### Scenario: Crear imagen
- **WHEN** se construye la imagen desde la carpeta del servicio
- **THEN** se genera una imagen ejecutable del microservicio Pagos en el puerto 8084
