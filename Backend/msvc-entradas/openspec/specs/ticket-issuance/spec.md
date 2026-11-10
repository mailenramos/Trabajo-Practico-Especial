# Especificación de emisión de entradas

## Purpose
Registrar la implementación actual de la emisión de entradas, incluidas la validación de solicitudes, la idempotencia, la gestión de concurrencia y las restricciones de PostgreSQL que protegen los asientos emitidos.

## Status
- **Implementado y probado con tests unitarios:** emisión exitosa, reintento idempotente con datos coincidentes, conflicto de idempotencia con datos distintos y rechazo de ubicaciones inválidas.
- **Verificado mediante test de integración con PostgreSQL en Testcontainers:** `EntradaConcurrencyIntegrationTest.java` comprueba la emisión concurrente para un mismo evento, sector, fila y número de asiento; verifica una emisión exitosa, conflictos `ENTRADA_VIGENTE_DUPLICADA` para las restantes y exactamente una entrada `VIGENTE` para ese lugar en PostgreSQL.
- **Sin verificación directa en tests:** persistencia y contenido de las copias de eventos y sectores; otras restricciones de base de datos fuera de las ejercitadas por los escenarios automatizados.
- **Límite del alcance comprobado:** `EntradaServiceImplTest` usa repositorios y una plantilla de transacción simulados; no prueba una instancia PostgreSQL ni endpoints HTTP.

## Requirements

### Requirement: Validar una solicitud de emisión
El servicio MUST rechazar un lugar numerado si falta la fila, rechazar un lugar no numerado si la fila no es nula y rechazar ubicaciones numeradas repetidas dentro de una solicitud antes de iniciar la transacción de emisión.

#### Scenario: Lugar numerado sin fila
- **WHEN** una solicitud de emisión contiene un lugar con número de asiento y sin fila
- **THEN** el servicio rechaza la solicitud con un error de validación y no ejecuta la plantilla de transacción

#### Scenario: Lugar no numerado con fila
- **WHEN** una solicitud de emisión contiene un lugar sin número de asiento y con una fila no nula
- **THEN** el servicio rechaza la solicitud con un error de validación y no ejecuta la plantilla de transacción

#### Scenario: Ubicación numerada repetida en una solicitud
- **WHEN** dos lugares tienen el mismo sector, fila y número de asiento
- **THEN** el servicio rechaza la solicitud con un error de validación y no ejecuta la plantilla de transacción

#### Scenario: Lugares no numerados
- **WHEN** varios lugares de una solicitud tienen fila y número nulos
- **THEN** la validación de ubicaciones del servicio no los detecta como duplicados

### Requirement: Hacer idempotente la emisión por identificador de compra
El servicio MUST devolver la emisión existente sin persistir otra cuando ya existe `idCompra` y el reintento coincide con el cliente, el identificador de evento y el conjunto de ubicaciones sector/fila/asiento almacenado. MUST devolver `IDEMPOTENCIA_CONFLICTO` cuando esos valores comparados sean distintos.

#### Scenario: Reintento coincidente
- **WHEN** se repite una emisión con el mismo `idCompra`, cliente, identificador de evento y ubicaciones
- **THEN** el servicio devuelve las entradas existentes e informa que no se creó una emisión nueva

#### Scenario: Reintento con datos comparados distintos
- **WHEN** se repite una emisión con el mismo `idCompra`, pero con otro cliente, identificador de evento o conjunto de ubicaciones
- **THEN** el servicio genera un conflicto de negocio con código `IDEMPOTENCIA_CONFLICTO`

#### Scenario: Cambian los datos descriptivos del evento o sector
- **WHEN** un reintento usa el mismo cliente, identificador de evento y ubicaciones, pero cambia el nombre/horario del evento o el nombre del sector
- **THEN** la comparación de idempotencia actual no considera esos valores descriptivos

### Requirement: Persistir transaccionalmente la emisión y sus entradas
El servicio MUST ejecutar la emisión dentro de una transacción, crear una entrada por cada lugar solicitado con un UUID de QR generado y estado inicial `VIGENTE`, y usar las restricciones de base de datos como protección final contra compras duplicadas y asientos numerados vigentes duplicados.

#### Scenario: Se emite una compra nueva
- **WHEN** no existe una emisión para la compra y todas las inserciones tienen éxito
- **THEN** el servicio persiste la emisión y las entradas solicitadas y devuelve un resultado de creación nueva

#### Scenario: Una solicitud concurrente colisiona por identificador de compra
- **WHEN** otra transacción gana la inserción única de `solicitudes_emision.id_compra`
- **THEN** el servicio captura el error de integridad, inicia una transacción nueva para buscar una emisión coincidente y la devuelve si existe

#### Scenario: El asiento numerado ya está vigente
- **WHEN** ya existe una entrada `VIGENTE` para el mismo evento, sector, fila y número de asiento
- **THEN** el índice único parcial de PostgreSQL rechaza el asiento vigente duplicado y el servicio convierte una colisión no resuelta en `ENTRADA_VIGENTE_DUPLICADA`

#### Scenario: Solicitudes concurrentes para el mismo asiento
- **WHEN** se procesan concurrentemente dos o más solicitudes de emisión con distintos `idCompra` para el mismo evento, sector, fila y número de asiento, y no existe previamente una entrada vigente para ese asiento
- **THEN** exactamente una solicitud se emite correctamente, todas las restantes terminan con el conflicto `ENTRADA_VIGENTE_DUPLICADA` y PostgreSQL contiene exactamente una entrada `VIGENTE` para ese asiento

## Technical Decisions and Constraints
- `idCompra` es un UUID con una restricción única en `solicitudes_emision.id_compra`. El servicio usa deliberadamente `TransactionTemplate` en lugar de un único método envolvente `@Transactional`, para que la búsqueda tras una colisión se ejecute en una transacción nueva.
- El índice PostgreSQL `uk_entrada_lugar_vigente` es único para `(id_evento, id_sector, fila, numero)` únicamente cuando `estado = 'VIGENTE' AND numero IS NOT NULL`. Las entradas que ya no están vigentes no reservan el asiento. Los lugares no numerados quedan fuera del índice y estas migraciones no definen una restricción de capacidad para ellos.
- Los identificadores de eventos y sectores se almacenan como texto. `insertarSiNoExiste` usa `ON CONFLICT DO NOTHING` de PostgreSQL; los nombres/detalles de copias existentes se conservan en vez de actualizarse.
- La comparación de reintentos verifica el cliente, el identificador del evento y las tuplas de ubicación ordenadas. No compara el nombre/horario del evento ni el nombre del sector.
- La restricción agregada por la migración V2 requiere que `fila` y `numero` sean ambos nulos o ambos no nulos. El servicio también valida que no se repitan ubicaciones numeradas dentro de una solicitud.
- `evento.comienzaEn` representa un instante (`Instant`) y se persiste en PostgreSQL como `TIMESTAMP WITH TIME ZONE`. La migración V3 interpreta explícitamente como UTC los valores anteriores sin zona.

## Verification Evidence
- `EntradaServiceImplTest`: pasan 15 tests; los de emisión cubren creación exitosa, reintento coincidente, reintento conflictivo y los tres casos de ubicación inválida.
- `EntradaConcurrencyIntegrationTest` usa el servicio Spring real y PostgreSQL mediante Testcontainers. Comprueba las migraciones Flyway, la emisión concurrente de distintas compras para el mismo asiento y el resultado persistido de una única entrada `VIGENTE`; no prueba endpoints HTTP ni todas las restricciones de la base de datos.
