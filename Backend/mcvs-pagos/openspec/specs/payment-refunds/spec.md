# payment-refunds Specification

## Purpose
Esta capacidad permite procesar de forma atómica solicitudes de reintegro del microservicio 3 y reintentar fallos sin duplicar operaciones.

## Requirements

### Requirement: Validar y crear reintegros
El sistema SHALL recibir `refundRequestId`, `amount`, `reason` e `Idempotency-Key` del microservicio 3 y no SHALL generarlos.

#### Scenario: Idempotency-Key ausente
- **WHEN** se crea un reintegro sin el header `Idempotency-Key`
- **THEN** responde HTTP 400 sin crear el reintegro

#### Scenario: Datos inválidos
- **WHEN** falta `refundRequestId`, `reason` está vacío, el monto no es positivo o tiene más de dos decimales
- **THEN** responde HTTP 400 sin crear el reintegro

#### Scenario: Payment inexistente
- **WHEN** el `paymentId` no existe
- **THEN** responde HTTP 404 sin crear el reintegro

### Requirement: Consultar reintegros
El sistema SHALL permitir consultar un reintegro por su identificador.

#### Scenario: Reintegro inexistente
- **WHEN** se consulta un reintegro que no existe
- **THEN** responde HTTP 404

### Requirement: Ordenar la validación de reintegros
El sistema SHALL adquirir primero `PESSIMISTIC_WRITE` sobre `Payment`, luego buscar la clave, validar `refundRequestId`, verificar pago `APROBADO`, validar saldo y recién después procesar.

#### Scenario: Reintegro repetido que agotó el saldo
- **WHEN** se repite una solicitud con la misma clave y huella de un reintegro ya resuelto, aunque el saldo esté agotado
- **THEN** responde HTTP 200 con el registro original sin volver a validar saldo ni procesar

#### Scenario: Pago no aprobado
- **WHEN** el pago existe pero está `RECHAZADO`
- **THEN** responde HTTP 409 sin procesar

#### Scenario: Refund request duplicado
- **WHEN** `refundRequestId` ya existe con otra clave
- **THEN** responde HTTP 409 sin crear otro reintegro

### Requirement: Validar saldo disponible
El sistema SHALL considerar `EXITOSO` y `PENDIENTE`, excluyendo `FALLIDO`, al validar que el acumulado no supere el importe cobrado.

#### Scenario: Reintegros parciales completan el importe cobrado
- **WHEN** un reintegro parcial hace que el acumulado de reintegros válidos sea exactamente igual al importe cobrado
- **THEN** se acepta el reintegro

#### Scenario: Reintegro fallido no consume saldo
- **WHEN** se calcula el saldo disponible para un nuevo reintegro y hay reintegros `FALLIDO`
- **THEN** esos reintegros se excluyen del acumulado

#### Scenario: Saldo excedido
- **WHEN** el nuevo monto supera el saldo permitido
- **THEN** responde HTTP 409

### Requirement: Resolver reintegros determinísticamente
El sistema SHALL resolver en una única transacción como `EXITOSO` o `FALLIDO`; `PENDIENTE` existe en el enum pero no se persiste como estado intermedio.

#### Scenario: Éxito
- **WHEN** la propiedad indica `SUCCESS`
- **THEN** responde HTTP 201 y persiste `EXITOSO`

#### Scenario: Fallo
- **WHEN** el simulador indica fallo
- **THEN** responde HTTP 201 y persiste `FALLIDO`

### Requirement: Reintentar reintegros fallidos
El endpoint de reintento SHALL no requerir `Idempotency-Key`; SHALL proteger la operación mediante el lock de `Payment` y permitir reintento solo si el reintegro está `FALLIDO`.

#### Scenario: Reintento válido
- **WHEN** el monto fallido más `EXITOSO + PENDIENTE` de otros reintegros no supera el cobro
- **THEN** actualiza el mismo UUID, incrementa `attemptCount` y responde HTTP 200

#### Scenario: Reintento excede saldo
- **WHEN** el monto del reintegro fallido más `EXITOSO + PENDIENTE` de otros reintegros supera el importe cobrado
- **THEN** responde HTTP 409, conserva `FALLIDO` y no incrementa `attemptCount`

#### Scenario: Simulador falla durante el reintento
- **WHEN** se reintenta un reintegro `FALLIDO` y el simulador indica fallo
- **THEN** responde HTTP 200, conserva el estado `FALLIDO` e incrementa `attemptCount`

#### Scenario: Simulador tiene éxito durante el reintento
- **WHEN** se reintenta un reintegro `FALLIDO` y el simulador indica éxito
- **THEN** responde HTTP 200, cambia el estado a `EXITOSO` e incrementa `attemptCount`

#### Scenario: Estado no reintentable
- **WHEN** se reintenta cualquier estado distinto de `FALLIDO`
- **THEN** responde HTTP 409 sin modificar el registro

#### Scenario: Reintegro inexistente al reintentar
- **WHEN** se solicita reintentar un reintegro que no existe
- **THEN** responde HTTP 404

#### Scenario: Dos reintentos simultáneos
- **WHEN** dos solicitudes reintentan simultáneamente el mismo reintegro
- **THEN** solo una prospera y la otra responde HTTP 409

### Requirement: Exponer el contrato REST de reintegros
El sistema SHALL exponer creación, consulta y reintento de reintegros.

#### Scenario: Códigos del contrato
- **WHEN** se invoca un endpoint de reintegro
- **THEN** una creación nueva responde 201, una repetición idempotente 200 y una consulta 200
