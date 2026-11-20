## Why

Esta documentación retrospectiva registra la adaptación ya implementada de MS2 para la Entrega 2, preservando las reservas y el inventario de la Entrega 1 y coordinando el acceso mediante la fila virtual simplificada. Los requisitos proceden de la consigna, la arquitectura acordada y las user stories; el código y las pruebas son evidencia, no fuente autónoma de requisitos.

## What Changes

- Consolidar US-4.1, US-4.2, US-4.4 y US-5.1 a US-5.4, conservando las responsabilidades de MS3 y MS4.
- Consultar a MS1 la política actual y, cuando corresponda, consumir un turno de MS7 de forma atómica e idempotente antes de crear reservas nuevas, sin HTTP bajo bloqueo local.
- Incorporar el resumen por sector, la exclusión de vencimientos lógicos del límite, identidad propagada, correlación y resiliencia.
- Registrar pruebas individuales MySQL y el Compose aislado con MS2 y MS7 reales. Los nombres `cantidadReservada` y `cantidadVendida` siguen sujetos a confirmación con MS1 y MS3.

## Capabilities

### New Capabilities

- `availability-reservations`: especificación retrospectiva completa de MS2 para la Entrega 2, incluidas garantías conservadas de la Entrega 1.

### Modified Capabilities

Ninguna en la especificación canónica: `openspec/specs` está vacío y el cambio histórico de Entrega 1 no está archivado. `ADDED` documenta una base completa para este cambio; no afirma que la funcionalidad haya sido creada en esta entrega ni archiva o modifica el historial.

## Impact

MS2 mantiene su base MySQL y outbox de disponibilidad, sus contratos de reserva y el control del inventario. MS1 sigue siendo autoridad de `requiereFila`, MS7 administra los turnos y MS3 las compras y devoluciones. El Gateway valida JWT y propaga identidad. La coordinación con MS1 real, MS3, MS6 y Gateway y los contratos del resumen requieren validación grupal. Sin transacciones distribuidas, un turno consumido puede quedar sin reserva; existe una ventana aceptada entre la autorización y la desactivación o cancelación posterior.
