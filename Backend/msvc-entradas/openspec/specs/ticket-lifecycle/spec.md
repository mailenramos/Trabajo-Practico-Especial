# Especificación del ciclo de vida de las entradas

## Purpose
Registrar los estados de las entradas, las transiciones admitidas (incluido el uso por validación de QR) y el cierre persistente de un evento cancelado.

## Status
- **Implementado y probado con tests unitarios:** estado inicial, transiciones individuales, idempotencia, `ENTRADA_UTILIZADA` al bloquear una entrada usada, cierre del evento (orden de bloqueos y respuesta `cerrado`).
- **Implementado, con tests de integración con PostgreSQL (Testcontainers; requieren Docker), ejecutados sin fallos el 2026-11-20** (`IngresoConcurrencyIntegrationTest`): validación contra bloqueo por devolución simultáneos, emisión contra cierre simultáneos, cierre sin entradas idempotente y conservación de las `UTILIZADA` al cerrar.

## Requirements

### Requirement: Crear entradas en estado vigente
Toda entrada recién construida MUST comenzar en estado `VIGENTE` y recibir un UUID de QR generado.

#### Scenario: Se crea una entrada durante la emisión
- **WHEN** el servicio crea una entrada para un lugar solicitado
- **THEN** la entrada tiene estado `VIGENTE` y un UUID de QR no nulo

### Requirement: Admitir las transiciones de estado de las entradas
El servicio MUST admitir `VIGENTE` → `UTILIZADA` (validación de QR, ver `ticket-access`), `VIGENTE` → `EN_REINTEGRO` y `EN_REINTEGRO` → `DEVUELTA`. Solo una de las transiciones que salen de `VIGENTE` MUST poder completarse. Repetir una transición cuyo destino ya sea el estado actual MUST dejar el estado sin cambios. Pasar a `EN_REINTEGRO` una entrada `UTILIZADA` MUST fallar con `ENTRADA_UTILIZADA`; cualquier otra transición no admitida MUST fallar con `TRANSICION_INVALIDA`. Una entrada `EN_REINTEGRO` SHALL NOT volver automáticamente a `VIGENTE`.

#### Scenario: Marcar una entrada vigente para reintegro
- **WHEN** una entrada está `VIGENTE` y se invoca la operación de reintegro
- **THEN** el servicio la cambia condicionalmente a `EN_REINTEGRO` y devuelve la entrada resultante

#### Scenario: Repetir la operación de reintegro
- **WHEN** una entrada ya está `EN_REINTEGRO`
- **THEN** la operación devuelve esa entrada sin ejecutar otra actualización de estado

#### Scenario: Bloquear una entrada ya utilizada
- **WHEN** se invoca la operación de reintegro sobre una entrada `UTILIZADA`, o una validación de QR se adelanta a la actualización
- **THEN** el servicio responde `409 ENTRADA_UTILIZADA` y la entrada no cambia

#### Scenario: Validación y bloqueo simultáneos
- **WHEN** una validación de QR y un bloqueo por devolución llegan a la vez para la misma entrada
- **THEN** exactamente uno se completa y la entrada termina `UTILIZADA` o `EN_REINTEGRO`

#### Scenario: Completar una devolución
- **WHEN** una entrada está `EN_REINTEGRO` y se invoca la operación de devolución
- **THEN** pasa a `DEVUELTA`; repetirla no cambia nada

#### Scenario: Intentar una transición inválida
- **WHEN** se invoca la operación de devolución para una entrada `VIGENTE` o `UTILIZADA`
- **THEN** el servicio genera un conflicto con código `TRANSICION_INVALIDA`

### Requirement: Usar actualizaciones de estado condicionales
Las actualizaciones individuales MUST incluir el ID de la entrada y el estado esperado en la condición del `UPDATE`. El servicio MUST volver a cargar la entrada después de intentar actualizarla y antes de decidir la respuesta.

#### Scenario: El estado cambia antes de la actualización condicional
- **WHEN** una actualización individual no afecta filas porque otra operación ya cambió el estado
- **THEN** el servicio vuelve a cargar la entrada y responde según su estado actual

### Requirement: Cerrar de forma persistente un evento cancelado
`POST /internal/eventos/{idEvento}/entradas/reintegro` MUST registrar el cierre del evento en `control_eventos` aunque no tenga entradas, y en la misma transacción MUST pasar a `EN_REINTEGRO` solo las entradas `VIGENTE` del evento; las `UTILIZADA` conservan su estado. MUST responder `{idEvento, entradasMarcadas, cerrado: true}` y ser idempotente. El cierre MUST tomar un bloqueo exclusivo sobre la fila de control del evento; emisión y validación toman un bloqueo compartido sobre la misma fila, de modo que una operación completada antes del cierre quede registrada y una posterior se rechace con `EVENTO_CERRADO`.

#### Scenario: Evento sin entradas
- **WHEN** se cierra un evento que no tiene entradas emitidas
- **THEN** el cierre se registra y la respuesta trae `cerrado: true` y `entradasMarcadas: 0`

#### Scenario: Repetir el cierre (idempotencia)
- **WHEN** se repite el cierre de un evento ya cerrado
- **THEN** responde `cerrado: true`, `entradasMarcadas: 0` y no modifica `cerrado_el`

#### Scenario: Emisión después del cierre
- **WHEN** llega una emisión nueva para un evento cerrado
- **THEN** se rechaza con `409 EVENTO_CERRADO` y no se crea ninguna entrada

#### Scenario: Emisiones simultáneas con el cierre
- **WHEN** varias emisiones y el cierre del mismo evento se ejecutan a la vez
- **THEN** cada emisión se completa antes del cierre (y sus entradas pasan a `EN_REINTEGRO`) o se rechaza con `EVENTO_CERRADO`, y no queda ninguna entrada `VIGENTE`

## Technical Decisions and Constraints
- El enum se persiste como texto; PostgreSQL restringe sus valores a `VIGENTE`, `UTILIZADA`, `EN_REINTEGRO` o `DEVUELTA` (migración V4).
- El índice único parcial `uk_entrada_lugar_ocupado` cuenta las entradas `VIGENTE` y `UTILIZADA`: un asiento usado sigue ocupado.
- `control_eventos` se crea con `insert ... on conflict do nothing` y se bloquea con `select ... for share` (emisión, validación) o `select ... for update` (cierre).
- Los cambios de estado de `entradas` usan `LocalDateTime` UTC; `cerrado_el` e `ingresos.validado_el` son `TIMESTAMP WITH TIME ZONE`.

## Verification Evidence
- `EntradaServiceImplTest`: transiciones, idempotencia, `ENTRADA_UTILIZADA`, `TRANSICION_INVALIDA` desde `UTILIZADA`, cierre y emisión con evento cerrado.
- `IngresoConcurrencyIntegrationTest` (Docker): validación contra bloqueo, emisión contra cierre, cierre idempotente sin entradas, `UTILIZADA` conservadas.
