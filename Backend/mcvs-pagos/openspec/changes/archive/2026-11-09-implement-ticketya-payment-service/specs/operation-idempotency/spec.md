# Spec Delta

## Purpose

Esta capacidad garantiza deduplicación, huellas consistentes y recuperación segura ante carreras de persistencia para pagos y reintegros.

## ADDED Requirements

### Requirement: Normalizar payloads para la huella
El sistema SHALL normalizar montos a escala 2 antes de calcular la huella SHA-256, de modo que representaciones monetarias equivalentes produzcan la misma huella.

#### Scenario: Escalas equivalentes
- **WHEN** dos solicitudes usan 50000.0 y 50000.00 con los demás datos iguales
- **THEN** se consideran el mismo payload idempotente

### Requirement: Deduplicar operaciones resueltas
El sistema SHALL buscar primero por clave en el dominio correspondiente y devolver HTTP 200 con el registro existente si la huella coincide.

#### Scenario: Repetición resuelta
- **WHEN** se repite una operación con la misma clave y payload después de resolverla
- **THEN** responde HTTP 200 con el mismo UUID y no ejecuta otro cobro o reintegro

### Requirement: Detectar conflictos de clave
El sistema SHALL responder HTTP 409 si una clave existente se usa con una huella diferente.

#### Scenario: Payload diferente
- **WHEN** cambia un dato relevante usando la misma clave
- **THEN** responde HTTP 409 sin modificar el registro original

### Requirement: Garantizar unicidad persistente separada
`Payment.idempotencyKey`, `Refund.idempotencyKey` y `Refund.refundRequestId` SHALL ser únicos en sus propias tablas.

#### Scenario: Refund request repetido con otra clave
- **WHEN** se usa un `refundRequestId` existente con otra clave
- **THEN** responde HTTP 409

### Requirement: Recuperar colisiones de persistencia
El método que captura `DataIntegrityViolationException` SHALL ser no transaccional, usar `saveAndFlush` dentro de la transacción creadora y buscar el registro ganador en una transacción nueva.

#### Scenario: Colisión de clave recuperable
- **WHEN** una carrera provoca una violación y existe un registro con esa clave
- **THEN** se busca en una transacción nueva y se responde HTTP 200 o 409 según la huella

#### Scenario: Colisión del índice parcial
- **WHEN** la violación no permite encontrar un registro con esa clave
- **THEN** se interpreta como colisión del índice parcial de compra aprobada y se responde HTTP 409

### Requirement: Ordenar la idempotencia de pagos
El sistema SHALL buscar por clave y solo si no existe verificar compra aprobada y procesar el pago.

#### Scenario: Clave existente
- **WHEN** la clave está registrada
- **THEN** se responde 200 o 409 sin verificar compra aprobada ni ejecutar simulación

### Requirement: Ordenar la idempotencia de reintegros
El sistema SHALL adquirir el lock del pago, buscar la clave y, si no existe, validar `refundRequestId`, estado del pago y saldo antes de procesar el reintegro.

#### Scenario: Repetir un reintegro resuelto que agotó el saldo
- **WHEN** se repite con la misma clave y huella un reintegro resuelto que agotó el saldo disponible
- **THEN** responde HTTP 200 con el reintegro existente sin volver a validar el saldo ni procesar otro reintegro

### Requirement: Proteger concurrencia
El sistema SHALL garantizar consistencia ante pagos y reintegros concurrentes mediante el índice único parcial de pagos aprobados y `PESSIMISTIC_WRITE` sobre `Payment`.

#### Scenario: Dos claves distintas para la misma compra
- **WHEN** dos solicitudes concurrentes con claves distintas intentan aprobar pagos para la misma compra
- **THEN** solo un pago queda `APROBADO` y la otra solicitud responde HTTP 409

#### Scenario: Dos reintegros concurrentes exceden el saldo
- **WHEN** dos reintegros simultáneos para el mismo pago, considerados juntos, superan el importe cobrado
- **THEN** no se permite que el acumulado válido supere ese importe y una solicitud conflictiva responde HTTP 409

#### Scenario: Dos reintentos concurrentes del mismo reintegro
- **WHEN** dos solicitudes reintentan simultáneamente el mismo reintegro
- **THEN** solo un reintento válido se procesa y la otra solicitud responde HTTP 409
