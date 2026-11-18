# Proposal

## Why

MS1 ya conserva una cancelación cuando Compras no está disponible, pero el pendiente está embebido en `Evento` y su reenvío conoce únicamente las cancelaciones. Para incorporar avisos de otros tipos sin duplicar este mecanismo, MS1 necesita una outbox persistente, transaccional y genérica que desacople el cambio local del procesamiento remoto.

## What Changes

- Agregar una colección MongoDB `outbox` con avisos estables, destino, payload, estado, intentos, planificación del próximo intento, errores y timestamps.
- Registrar el cambio local de cancelación y su aviso a Compras en la misma transacción MongoDB.
- Migrar la entrega inmediata y los reintentos de `POST /eventos-cancelados` al dispatcher general; cualquier `2xx` se considera recepción exitosa y los fallos de Compras no cambian la respuesta al administrador.
- Incorporar selección atómica, recuperación de avisos `PROCESANDO` vencidos, máximo de 8 intentos y backoff progresivo de 30 s × 2 hasta 15 min, sin jitter. Solo el intervalo del scheduler es configurable; el límite, backoff y lease son fijos.
- Configurar `MongoTransactionManager`; el entorno ya usa MongoDB como replica set de un nodo (`rs0`).
- Migrar al iniciar los pendientes legacy con un backfill idempotente y retirar el flag y el scheduler dedicados.
- No modificar Docker Compose ni agregar en este cambio productores de avisos para publicación o modificación de eventos; esos cambios dependerán de esta infraestructura.
- Actualizar la especificación existente `eventos`, sin crear una capacidad paralela.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: formalizar la outbox transaccional y el ciclo de entrega/reintento del aviso de cancelación a Compras.

## Impact

Afecta la persistencia MongoDB de MS1, la configuración transaccional, el servicio de ciclo de vida de eventos, el cliente HTTP a Compras y las pruebas de integración. El aviso conserva `POST /eventos-cancelados` y su payload actual; el identificador estable se envía en `X-Aviso-Id` sin cambiar el payload y Compras puede continuar deduplicando por `eventoId`.

Las políticas de clasificación HTTP, reintentos, lease, backfill y revisión de `ERROR_FINAL` se detallan en `design.md`.
