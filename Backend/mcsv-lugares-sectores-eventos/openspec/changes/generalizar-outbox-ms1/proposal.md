# Proposal

## Why

MS1 ya conserva una cancelación cuando Compras no está disponible, pero el pendiente está embebido en `Evento` y su reenvío conoce únicamente las cancelaciones. Para incorporar avisos de otros tipos sin duplicar este mecanismo, MS1 necesita una outbox persistente, transaccional y genérica que desacople el cambio local del procesamiento remoto.

## What Changes

- Agregar una colección MongoDB `outbox` con avisos estables, destino, payload, estado, intentos, planificación del próximo intento, errores y timestamps.
- Registrar el cambio local de cancelación y su aviso a Compras en la misma transacción MongoDB.
- Migrar la entrega inmediata y los reintentos actuales de `POST /eventos-cancelados` al dispatcher general, conservando la respuesta y el comportamiento ante Compras caído.
- Incorporar selección atómica, recuperación de avisos `PROCESANDO` vencidos, reintentos limitados y espera progresiva configurable.
- Configurar `MongoTransactionManager`; el entorno ya usa MongoDB como replica set de un nodo (`rs0`).
- No modificar Docker Compose ni agregar en este cambio productores de avisos para publicación o modificación de eventos; esos cambios dependerán de esta infraestructura.
- Actualizar la especificación existente `eventos`, sin crear una capacidad paralela.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: formalizar la outbox transaccional y el ciclo de entrega/reintento del aviso de cancelación a Compras.

## Impact

Afecta la persistencia MongoDB de MS1, la configuración transaccional, el servicio de ciclo de vida de eventos, el cliente HTTP a Compras y las pruebas de integración. El aviso conserva su endpoint y payload actuales; la transmisión de un identificador de aviso requiere confirmar el contrato de deduplicación con Compras.

Las políticas acordadas de reintentos, lease, deduplicación y recuperación operativa se detallan en `design.md`.
