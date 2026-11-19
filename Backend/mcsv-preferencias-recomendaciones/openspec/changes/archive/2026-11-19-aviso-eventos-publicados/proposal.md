# Proposal

## Why

Cuando MS1 publica un evento, los clientes cuyas preferencias coinciden tanto con sus artistas participantes como con su ciudad deben recibir un aviso. MS8 ya almacena esas preferencias y puede actuar como puente entre la outbox de MS1 y Notificaciones sin guardar estado adicional.

## What Changes

- Agregar `POST /internal/preferencias/eventos-publicados`, autenticado exclusivamente con `X-Service-Token` y `SERVICIO_TOKEN`; no requiere identidad de usuario ni se agrega al Gateway.
- Validar el evento recibido y seleccionar una sola vez a cada usuario que coincida por artista y ciudad, ignorando mayúsculas y espacios exteriores.
- Enviar a Notificaciones un evento `NUEVO_EVENTO_DE_INTERES` por destinatario, con la fecha del evento formateada para Buenos Aires, correlación trazable y token de servicio.
- Intentar todos los envíos en secuencia. Responder `202` con los contadores cuando todos sean aceptados o no haya destinatarios; responder `503` si falla alguno para permitir el reintento de la outbox.
- Configurar la URL de Notificaciones, documentar las simplificaciones y el uso del stub existente en README y agregar una solicitud interna a Postman.

## Capabilities

### New Capabilities

- `avisos-eventos-interes`: recepción autenticada de publicaciones de MS1, selección de clientes interesados y entrega de avisos a Notificaciones.

### Modified Capabilities

Ninguna.

## Impact

- Nuevo contrato interno de MS8 en `POST /internal/preferencias/eventos-publicados`, protegido con token de servicio y sin cambios al Gateway.
- Cambios acotados a los paquetes API, seguridad, preferencias e integración HTTP de MS8; sin colección Mongo nueva ni dependencias externas nuevas.
- Actualización de `application.yml`, `docker-compose.yml`, README y colección Postman dentro de MS8.
- La idempotencia de reintentos depende de la deduplicación confirmada por Notificaciones mediante `acontecimientoId`, `tipo` y `usuarioId`.
