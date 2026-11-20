# Propuesta: trazabilidad y correlación

## Why

MS1 necesita registrar cada solicitud y relacionar sus llamadas entre microservicios, incluidas las entregas diferidas de la outbox, sin exponer secretos en los logs.

## What Changes

- Agregar un filtro HTTP que acepte IDs de hasta 100 caracteres alfanuméricos, guion, guion bajo o punto, o genere un UUID; usar MDC y devolver el header. Registrar método, ruta sin query, usuario cuando exista, estado y duración, excepto en `/actuator/**`.
- Incluir `correlationId` en el patrón de Logback y mantener la salida por stdout, sin registrar tokens, cuerpos ni contraseñas.
- Propagar el identificador a Reservas y en todas las entregas outbox; guardar el contexto en los avisos nuevos y generar un ID al despachar avisos antiguos sin él.
- Dejar previsto que el futuro cliente de MS6 use el mismo mecanismo si se implementa.

## Capabilities

### New Capabilities

- `trazabilidad-correlacion`: correlación HTTP y asíncrona, propagación y registro seguro.

### Modified Capabilities

Ninguna; se mantienen los contratos de eventos y las reglas de entrega outbox.

## Impact

Filtro y configuración de logging de MS1, clientes HTTP, modelo y dispatcher de outbox, pruebas y README. No se agrega autenticación, infraestructura de observabilidad ni cambios en el Gateway o en otros microservicios; Loki y Alloy corresponden al Compose general.
