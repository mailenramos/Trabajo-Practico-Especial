# Especificación del ciclo de vida de las entradas

## Purpose
Registrar los estados implementados de las entradas y las transiciones admitidas para entradas individuales y cancelaciones de eventos.

## Status
- **Implementado y probado con tests unitarios:** estado inicial, transiciones individuales, repetición idempotente de transiciones y rechazo de la transición inválida directa de `VIGENTE` a `DEVUELTA`.
- **Implementado, pero sin tests de integración con la base de datos:** actualizaciones condicionales masivas y comportamiento concurrente en PostgreSQL.
- **No cubierto por los tests unitarios actuales:** carreras concurrentes de cambios de estado y persistencia de `actualizado_el`.

## Requirements

### Requirement: Crear entradas en estado vigente
Toda entrada recién construida MUST comenzar en estado `VIGENTE` y recibir un UUID de QR generado.

#### Scenario: Se crea una entrada durante la emisión
- **WHEN** el servicio crea una entrada para un lugar solicitado
- **THEN** la entrada tiene estado `VIGENTE` y un UUID de QR no nulo

### Requirement: Admitir las transiciones de estado de las entradas
El servicio MUST admitir las transiciones de `VIGENTE` a `EN_REINTEGRO` y de `EN_REINTEGRO` a `DEVUELTA`; repetir una transición cuyo destino ya sea el estado actual MUST dejar el estado sin cambios. Una transición directa de `VIGENTE` a `DEVUELTA` MUST fallar con `TRANSICION_INVALIDA`.

#### Scenario: Marcar una entrada vigente para reintegro
- **WHEN** una entrada está `VIGENTE` y se invoca la operación de reintegro
- **THEN** el servicio la cambia condicionalmente a `EN_REINTEGRO` y devuelve la entrada resultante

#### Scenario: Repetir la operación de reintegro
- **WHEN** una entrada ya está `EN_REINTEGRO`
- **THEN** la operación devuelve esa entrada sin ejecutar otra actualización de estado

#### Scenario: Completar una devolución
- **WHEN** una entrada está `EN_REINTEGRO` y se invoca la operación de devolución
- **THEN** el servicio la cambia condicionalmente a `DEVUELTA` y devuelve la entrada resultante

#### Scenario: Repetir la operación de devolución
- **WHEN** una entrada ya está `DEVUELTA`
- **THEN** la operación devuelve esa entrada sin ejecutar otra actualización de estado

#### Scenario: Intentar una transición inválida
- **WHEN** se invoca la operación de devolución para una entrada `VIGENTE`
- **THEN** el servicio genera un conflicto con código `TRANSICION_INVALIDA`

### Requirement: Usar actualizaciones de estado condicionales
Las actualizaciones individuales MUST incluir tanto el ID de la entrada como el estado actual esperado en la condición de actualización de la base de datos. El servicio MUST volver a cargar la entrada después de intentar actualizarla y antes de decidir la respuesta.

#### Scenario: El estado cambia antes de la actualización condicional
- **WHEN** una actualización individual no afecta filas porque otra operación ya cambió el estado
- **THEN** el servicio vuelve a cargar la entrada y solo la devuelve si su estado actual es el destino solicitado; de lo contrario, genera un conflicto de transición inválida

### Requirement: Pasar a reintegro las entradas vigentes de un evento
La operación de cancelación MUST cambiar atómicamente a `EN_REINTEGRO` solo las entradas del evento solicitado que estén `VIGENTE`, y MUST devolver la cantidad de filas modificadas.

#### Scenario: Se repite la cancelación de un evento
- **WHEN** se repite la operación cuando ya no quedan entradas `VIGENTE` para ese evento
- **THEN** devuelve cero entradas marcadas

## Technical Decisions and Constraints
- El enum de estados se persiste como texto y PostgreSQL restringe sus valores a `VIGENTE`, `EN_REINTEGRO` o `DEVUELTA`.
- Las actualizaciones del repositorio usan `UPDATE ... WHERE estado = :esperado`; la variante por evento aplica la misma condición a todas las entradas que coinciden con el identificador del evento. Los métodos devuelven la cantidad de filas afectadas.
- Los cambios de estado establecen explícitamente `actualizado_el` con un `LocalDateTime` UTC; el inicio de la aplicación y la configuración JDBC de Hibernate también seleccionan UTC.
- El test de servicio verifica el comportamiento de los métodos con resultados simulados del repositorio. No verifica el aislamiento transaccional real de PostgreSQL ni actualizaciones concurrentes.

## Verification Evidence
- `EntradaServiceImplTest` cubre ambas transiciones individuales permitidas, las llamadas repetidas idempotentes, la transición directa inválida y la cantidad devuelta por la cancelación de evento.
- Los tests actuales no ejecutan contra una base real la persistencia masiva por evento ni los casos concurrentes.
