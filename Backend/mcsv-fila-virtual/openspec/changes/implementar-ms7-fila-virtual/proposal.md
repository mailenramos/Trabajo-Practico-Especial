## Why

Este cambio es documentación retrospectiva de MS7, ya implementado para la Entrega 2. Registra F7 y sus nueve historias con la fila virtual simplificada acordada, cuya responsabilidad es regular el acceso y no controlar inventario.

## What Changes

- Documentar una fila persistente FIFO por evento, con cupo y duración configurables, ingreso, posición, habilitación, vencimiento, consumo y administración.
- Precisar consumo atómico e idempotente, coordinación administrativa con MS1, cancelación e integración con MS2 y Gateway.
- Registrar seguridad, resiliencia, concurrencia, observabilidad, ejecución Docker y evidencia de pruebas.
- Mantener fuera de alcance preautorización en dos fases, registro adicional de operaciones, generaciones, políticas versionadas y outbox para la fila.

## Capabilities

### New Capabilities

- `virtual-queue`: gestión simplificada de fila virtual por evento y su integración con MS1 y MS2.

### Modified Capabilities

Ninguna: MS7 no tenía estructura ni especificación canónica OpenSpec previa.

## Impact

MS7 usa su propia base MySQL y expone cinco endpoints públicos y dos internos. MS1 mantiene la política `requiereFila`, MS2 conserva el inventario y consume turnos. Las integraciones con MS1, MS6 y Gateway reales y las pruebas grupales permanecen pendientes; los resultados con MS1 simulado no las sustituyen.
