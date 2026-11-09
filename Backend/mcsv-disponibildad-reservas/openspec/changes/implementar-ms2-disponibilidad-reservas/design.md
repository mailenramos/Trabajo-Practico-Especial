## Context

MS2 es un microservicio Spring Boot con base relacional MySQL propia. MS1 es autoridad sobre los datos generales y ciclo de vida del evento; MS2 es autoridad sobre inventario y reservas; MS3 coordina compras y devoluciones; MS4 procesa pagos y reintegros; MS5 administra entradas. Las integraciones son REST/JSON directas entre servicios, fuera del Gateway y sin autenticación de servicio. Las operaciones de clientes pasan por el Gateway. Puertos locales: MS1 `8081`, MS2 `8080` y MS3 `8083`.

## Goals / Non-Goals

**Goals:**
- Preservar inventario y transiciones de reserva en transacciones MySQL seguras ante concurrencia.
- Definir rutas y payloads consistentes para MS1, MS3 y clientes.
- Mantener una fuente de verdad de disponibilidad para cada tipo de sector.
- Hacer idempotentes los reintentos que puedan repetir reservas, confirmaciones, liberaciones y notificaciones.

**Non-Goals:**
- Administrar catálogo, precio, compra, pago, reintegro monetario o emisión/anulación de entradas en MS2.
- Acceder a bases de datos ajenas o introducir transacciones distribuidas.
- Autenticar microservicios entre sí. JWT de usuarios pertenece a la segunda entrega.

## Decisions

### Identificadores e inventario

- `eventoId`, `sectorId` y `clienteId` son String. `fila` y `numero` son Integer.
- La identidad de un asiento numerado es exclusivamente `(eventoId, sectorId, fila, numero)`. No hay `lugarId` ni `asientoId` de negocio. `numero` es positivo; no se añade una regla de positividad a `fila` sin acuerdo.
- Dentro de un evento, `sectorId` identifica un sector único; la persistencia conserva unicidad por `(eventoId, sectorId)`. Dentro de un sector numerado, cada `(fila, numero)` aparece una sola vez, consistente con la clave única del asiento `(eventoId, sectorId, fila, numero)`.
- Sectores numerados: los estados individuales `LIBRE`, `RESERVADO` y `VENDIDO` son fuente de verdad; la disponibilidad es el conteo de `LIBRE`.
- Sectores no numerados: `capacidadTotal`, `cantidadReservada` y `cantidadVendida` son la fuente de verdad; disponibilidad es `capacidadTotal - cantidadReservada - cantidadVendida`. No se almacena un contador independiente de disponibles. En ambos tipos de sector, `cantidadDisponible` puede ser cero por reservas temporales; esa condición no constituye agotamiento.
- Las cantidades reservadas/confirmadas por `(clienteId, eventoId)` se actualizan en la misma transacción que cada transición de reserva/venta para no divergir del inventario.
- Referencias a otros servicios son escalares, sin asociación JPA ni FK física.

### Inicialización y límite

MS1 (puerto local `8081`) envía `PUT /disponibilidad/eventos/{eventoId}` a MS2 (puerto local `8080`) con `eventoId` en la URL, `limiteEntradasPorCliente: 4`, `sectores` y `asientos`. El límite es fijo: no depende de una configuración del organizador ni puede variar entre eventos. MS2 valida que el valor recibido sea exactamente 4. Un sector numerado incluye inventario completo con `fila` y `numero` Integer; un sector no numerado incluye `asientos: []`. `sectorId` es único dentro del evento; la lista numerada no repite la combinación `(fila, numero)`. MS2 persiste evento, sectores y asientos como una unidad y crea el evento en `PENDIENTE_PUBLICACION`. La nueva inicialización y el reintento idéntico responden `204 No Content`, solo después del commit.

La lista de asientos numerados representa el inventario completo del sector. La especificación no determina explícitamente si `capacidadTotal` debe ser exactamente igual a la cantidad de elementos de `asientos`; no imponer ni validar esa igualdad hasta que el equipo confirme la regla.

Un reintento con el mismo contenido no duplica ni reinicia datos. Contenido diferente para un `eventoId` ya inicializado responde `409`. El límite fijo de 4 queda fijado desde la inicialización y no se modifica posteriormente; no se define ni se expone una operación para actualizarlo. Se rechaza la ausencia del campo o cualquier valor distinto de 4.

Las nuevas huellas de inicialización usan el formato versionado `v2:<sha256>`, con filas enteras ordenadas numéricamente y serializadas como enteros. Una huella heredada sin versión explícita no se acepta como prueba de reintento idéntico; mientras no exista una estrategia de compatibilidad aprobada, MS2 falla de forma cerrada y responde conflicto para esos eventos.

### Estado de venta y autoridad de MS1

MS1 envía `PUT /disponibilidad/eventos/{eventoId}/venta` con `{ "estado": "..." }` y uno de `ACTIVA`, `SUSPENDIDA`, `CANCELADA`, `FINALIZADA`. El contrato HTTP conserva los cuatro valores. La tarea 2.5 implementa publicación/activación, suspensión y reanudación (`ACTIVA`/`SUSPENDIDA`); la tarea 4.4 agrega cancelación y finalización (`CANCELADA`/`FINALIZADA`) cuando el ciclo de reservas y liberación está disponible. No se duplican rutas ni responsabilidades. MS1 sigue siendo la autoridad; MS2 solo mantiene el estado local requerido para la disponibilidad y elegibilidad.

- `ACTIVA`: evento publicado y ventas habilitadas; al publicar por primera vez permite la transición desde `PENDIENTE_PUBLICACION`.
- `SUSPENDIDA`: evento publicado, ventas suspendidas; bloquea nuevas reservas y conserva las vigentes.
- `CANCELADA` y `FINALIZADA`: bloquean nuevas reservas y liberan reservas temporales vigentes; ventas confirmadas permanecen registradas. MS2 solo aplica el cambio de estado local y libera sus reservas temporales; la coordinación general de cancelaciones, reintegros e invalidación de entradas corresponde a los microservicios responsables (MS3 con MS4/MS5).
- Una transición correcta nueva y repetir el estado actual responden `204`; estado de entrada inválido responde `400`; evento sin disponibilidad inicializada en MS2 responde `404`; transición incompatible responde `409`; error interno responde `500`. Para contenido no soportado se conserva la respuesta común `415`.

El enum Java persistente actual usa `PUBLICADO`, `CANCELADO`, `FINALIZADO`, `AGOTADO`, `PENDIENTE_PUBLICACION` y un booleano `ventaSuspendida`. La regla funcional de los cuatro valores de integración está acordada. `PENDIENTE_PUBLICACION` pasa a publicado cuando MS2 recibe `ACTIVA` en el endpoint de estado de venta; no se añade otra notificación ni ruta. El ciclo de vida del evento, la suspensión de ventas y el agotamiento son dimensiones distintas: `ventaSuspendida` representa suspensión; el agotamiento se deriva del inventario confirmado y no sustituye `PUBLICADO`, `CANCELADO` o `FINALIZADO`. La solución no persiste `AGOTADO` para esta condición y no cambia `EstadoEvento`; el enum conserva ese valor por compatibilidad, pero la notificación se calcula antes y después de cada transición de ventas confirmadas.

### Contratos REST

| Dirección y canal | Operación | Resultado |
| --- | --- | --- |
| MS1 → MS2, directa | `PUT /disponibilidad/eventos/{eventoId}` | Nueva inicialización y reintento idéntico: 204 tras commit; datos distintos: 409 |
| MS1 → MS2, directa | `PUT /disponibilidad/eventos/{eventoId}/venta` | Contrato admite los cuatro valores; 2.5 implementa ACTIVA/SUSPENDIDA y 4.4 CANCELADA/FINALIZADA; 204 correcto/repetido, 400 estado inválido, 404 sin inicialización local, 409 transición incompatible, 500 error interno |
| MS1 → MS2, directa | `GET /disponibilidad/eventos/{eventoId}` | 200 con disponibilidad; 404 si MS2 no tiene disponibilidad local; 500 por falla interna no recuperable |
| MS2 → MS1, directa | `PUT /eventos/{eventoId}/disponibilidad` | Body `{ "agotado": true|false }`, solo al cambiar condición; MS1 responde 204 al procesar (también repetido/tardío), 404 si no existe y 409 solo si está en borrador |
| Cliente → MS2, vía Gateway | `POST /disponibilidad/eventos/{eventoId}/reservas` | Crea reserva completa o ninguna; `(clienteId, solicitudId)` identifica la solicitud y sus reintentos |
| MS3 → MS2, directa | `GET /disponibilidad/reservas/{reservaId}` | 200 con titular, estado, vencimiento y detalles; 404 si no existe localmente; 500 por falla interna no recuperable |
| MS3 → MS2, directa | `POST /disponibilidad/reservas/{reservaId}/confirmacion` | Confirma tras pago aprobado; éxito, incluida repetición confirmada: 204; no encontrada: 404; vencida/liberada: 409; falla interna: 500 |
| MS3 → MS2, directa | `POST /disponibilidad/reservas/{reservaId}/liberacion` | Pago rechazado o devolución aprobada; éxito/repetición procesada: 204; datos inválidos: 400; no encontrada: 404; estado incompatible: 409; falla interna: 500 |

Para llamadas de cliente, el Gateway gestiona la autorización y transmite el encabezado compartido `X-User-Role` con uno de estos valores exactos y sensibles a mayúsculas: `CLIENTE`, `ORGANIZADOR` o `ADMIN`. Todos los microservicios comparten este vocabulario. MS2 usa `X-User-Id` como titular de una reserva y no lo sustituye por un dato del body; no exige ni valida `X-User-Role` en sus endpoints. Esta propagación no agrega autenticación entre microservicios: sus llamadas siguen siendo REST/JSON directas, fuera del Gateway y sin credenciales de servicio.

El requisito existente de que el cliente pueda consultar una reserva propia se conserva. Su ruta pública y formato de respuesta no están incluidos en los acuerdos actuales; deben confirmarse antes de añadir otra operación al OpenAPI. No se sustituye por la consulta interna de MS3.

### Reservas, idempotencia y límite

- Cada reserva temporal dura exactamente 10 minutos; el plazo es fijo y no configurable. El máximo es exactamente 4 entradas por cliente y evento, también fijo y no configurable. Se registran reservas vigentes y ventas confirmadas en los contadores por cliente/evento. Se reservan todos los detalles o ninguno.
- La identidad de solicitud es `(clienteId, solicitudId)` y no incluye el evento. El cliente conserva el ID en reintentos. La comparación de contenido incluye tanto el `eventoId` de la URL como el contenido del body; por ello, reutilizar el mismo ID del mismo cliente bajo otro `eventoId` es contenido diferente y responde `409 Conflict`. Si coinciden cliente, ID, `eventoId` y contenido del body, y la reserva original sigue `VIGENTE` y su instante de expiración aún no pasó, MS2 responde `200 OK` con la reserva existente, sin reservar nuevamente. Contenido distinto para la misma combinación responde `409 Conflict` y no modifica la reserva original. Si la reserva asociada venció (incluso si la expiración automática aún no la cambió de estado) o fue liberada, MS2 responde `409 Conflict` indicando que la reserva original ya no está vigente; no crea otra automáticamente. Una solicitud nueva con ID nuevo responde `201 Created`.
- La creación contempla `400` para datos inválidos, `404` si falta disponibilidad local, `409` para conflictos y `500` para errores internos no recuperables; `415` aplica a un tipo de contenido no soportado.
- `Reserva.id` es UUID String. `Reserva.usuarioId` almacena el identificador de `X-User-Id`; el contrato lo expone como `clienteId`. `eventoId` y `sectorId` son String. Detalles numerados llevan `fila`/`numero` Integer; los no numerados llevan `cantidad` positiva.
- Estados de reserva: `VIGENTE`, `CONFIRMADA`, `LIBERADA`. Confirmar exige `VIGENTE` y `expiraEn` posterior al instante actual. Un vencimiento real se rechaza aun si el proceso automático no corrió. Repetir confirmación confirmada responde 204; vencida y liberada responden 409 con códigos diferenciados `RESERVA_VENCIDA` y `RESERVA_LIBERADA`.
- Liberar por `PAGO_RECHAZADO` libera toda la reserva temporal; no convierte `CONFIRMADA` en liberada. Para `DEVOLUCION_APROBADA`, MS3 envía `devolucionId` (UUID global generado por MS3) y el subconjunto de detalles. MS2 primero reconoce los reintentos ya procesados por `devolucionId`: misma reserva/detalles responde `204` sin repetir efectos; otra reserva o detalles distintos responde `409`. Para un ID nuevo, cada detalle debe pertenecer a la reserva indicada por `reservaId`, que debe estar confirmada, y corresponder solo a lugares vendidos/cantidades confirmadas aún no devueltos; en caso contrario se rechaza sin modificar datos. Un lugar o cantidad ya devuelto tampoco puede liberarse otra vez mediante otro UUID; se impide una segunda liberación y un segundo decremento del agregado.
- V5 conserva el ledger fuera de `reservas`: `devoluciones.id` es la clave primaria global del UUID y almacena la reserva y la huella SHA-256 de detalles normalizados. Las tablas de detalle vinculan cada devolución a la reserva y al detalle de venta original mediante claves foráneas compuestas. Un índice único sobre `detalle_reserva_id` permite devolver una sola vez cada asiento numerado; el ledger no numerado registra cantidades positivas por devolución y detalle original, y las cantidades acumuladas se validan bajo bloqueo de la reserva. V5 agrega además la clave única local `(reserva_id,id)` necesaria para esas referencias. La escritura del ledger, transición de inventario y decremento de `cantidadConfirmada` comparten una transacción.
- La fecha `expiraEn` se representa en UTC ISO-8601 con `Z`. `LocalDateTime` no contiene zona horaria; su conversión debe representar el instante UTC real, no añadir el sufijo sin conversión.
- MS2 valida concurrencia y el máximo fijo de 4 dentro de la misma frontera transaccional que actualiza reserva e inventario. No hay límite configurable por evento ni operación para cambiarlo.

### Devoluciones y coordinación entre servicios

Para devolución aprobada, MS3 invalida primero la entrada en MS5; luego solicita a MS2 la liberación parcial; finalmente MS3 solicita el reintegro a MS4. MS2 actualiza inventario y cantidad confirmada en una transacción local; no procesa reintegros ni modifica entradas. No existe transacción distribuida.

### Agotamiento y notificación

Un evento está agotado cuando todos sus lugares están vendidos: en numerados, todos los asientos `VENDIDO`; en no numerados, capacidad vendida completa. Reservas temporales no cuentan. `cantidadDisponible` puede ser cero por holds vigentes sin que el evento esté agotado. Agotamiento es independiente del ciclo de vida del evento y del flag `ventaSuspendida`. MS2 envía `PUT /eventos/{eventoId}/disponibilidad` con `agotado: true` al agotarse y `false` cuando vuelve a haber disponibilidad. Solo notifica un cambio de condición. Si una devolución aprobada cambia el evento de agotado a no agotado mientras las ventas siguen suspendidas, MS2 notifica `agotado: false` sin limpiar `ventaSuspendida`, reactivar ventas ni cambiar el ciclo de vida; esa notificación informa únicamente la condición del inventario.

La condición se deriva de los asientos `VENDIDO` y de `cantidadVendida`; MS2 no persiste un estado `AGOTADO` ni cambia `EstadoEvento` al calcularla. Confirmación y devolución aprobada toman primero un bloqueo pesimista de la fila del evento, calculan la condición antes y después del cambio y guardan una fila Outbox solo cuando difiere. La inicialización no encola un estado inicial. La fila Outbox se escribe en la misma transacción que inventario y cantidades; el envío HTTP queda fuera de ella.

V6 crea `notificaciones_disponibilidad_outbox` con el evento, valor `agotado`, fecha UTC de creación, estado, intentos, próximo intento, lease/token del worker, fecha de envío y último error. Índices permiten localizar candidatos y conservar el orden por evento; CHECKs restringen estados y combinaciones válidas del lease. El dispatcher reclama una fila en una transacción breve con bloqueo, token y lease, envía REST con timeouts configurables y luego marca `ENVIADA` solo ante `204`. Ante transporte fallido o cualquier otro status, conserva la fila y programa reintentos exponenciales con demora máxima configurable y sin máximo de intentos; errores quedan trazados sin guardar detalles sensibles de respuesta. Un lease vencido permite recuperar trabajo tras un reinicio. Las notificaciones del mismo evento se procesan en orden; pueden repetirse si el proceso cae después de que MS1 aceptó el request y antes de guardar el `204`, por lo que el receptor idempotente mantiene la consistencia. El endpoint y body no agregan identificadores ni campos.

La URL base de MS1 se configura mediante `MS1_API_BASE_URL`/`ms1.api.base-url` (por defecto local `http://localhost:8081`); la frecuencia de polling, timeouts, lease, demora inicial/máxima y tamaño del lote son propiedades configurables. MS1 confirma con `204` que procesó la notificación, incluso si recibe el mismo valor o una notificación tardía de un evento cancelado/finalizado; responde `404` si el evento no existe y `409` únicamente si sigue en borrador. Todos los resultados distintos de `204` se conservan para reintento.

### Política propuesta de errores

MS2 utiliza el formato y el catálogo de errores acordados: `timestamp`, `status`, `error`, `mensaje` y `campos` opcional, como lista de `{ "campo": "...", "mensaje": "..." }`. No se exponen SQL, excepciones internas ni stack traces.

Comportamientos ya definidos: datos inválidos 400; disponibilidad local inexistente 404, sin afirmar que el evento no exista globalmente; contenido de inicialización distinto o transición incompatible 409; tipo de contenido no soportado 415; fallas internas no recuperables 500. Se aplica el formato y catálogo de errores acordados.

## Decisions Pending Confirmation

- Confirmar si, para cada sector numerado, `capacidadTotal` debe ser exactamente igual al número de elementos de su lista completa `asientos`; hasta entonces, no imponer ni validar esa igualdad.
- Tratamiento de datos heredados de `fila` no numéricos, fuera del rango de `Integer` o que colisionen al normalizarse; V4 se detiene antes del DDL ante esos casos y no asigna valores ni elimina filas. El inventario real por entorno y la remediación de cualquier registro incompatible siguen pendientes.
- Compatibilidad de los reintentos de inicialización para eventos cuya huella almacenada es el SHA-256 sin versión generado por el algoritmo anterior. MS2 no acepta esas huellas como idénticas sin poder verificarlas; falta decidir si se comparará el inventario persistido, se preservará una forma verificable de la huella previa o se tratarán esos eventos como no reintentables.

## Dependencias de implementación y estado de verificación

1. El código y V4 alinean `fila: Integer` en entidades, DTOs y persistencia. V4 se verificó en esquema MySQL temporal; antes de aplicarla en cada entorno hay que inventariar los datos y confirmar que sean convertibles sin colisiones, sin reescribir migraciones ya aplicadas.
2. La inicialización y consulta HTTP para MS1, incluida la idempotencia de inicialización, están implementadas; el mismo contenido responde 204 y el diferente 409.
3. Publicación/activación, suspensión, reanudación, cancelación y finalización se implementan mediante el estado local `EstadoEvento` y `ventaSuspendida`, sin agregar estados persistidos.
4. El agregado cliente/evento se mantiene transaccionalmente sincronizado con las transiciones de reservas y ventas; 1.4 y 3.5 se verificaron con pruebas MySQL temporales.
5. La creación idempotente, vencimiento, consulta, confirmación y liberación por pago rechazado están implementados y cuentan con pruebas HTTP/MySQL.
6. El ledger global de devoluciones parciales está implementado en V5 y verificado en pruebas MySQL temporales.
7. El Outbox durable y su envío/reintentos a MS1 están implementados en V6 y fueron incluidos en la verificación MySQL temporal.
8. La conversión UTC de `expiraEn` y su conservación al persistir/recuperar cuentan con pruebas HTTP y MySQL round-trip; no se añade solamente el sufijo `Z`.

La secuencia terminal es acíclica: 2.1/2.2 → 2.5 (ACTIVA/SUSPENDIDA) → 3.1–3.5 → 4.1 y 4.3 → 4.4 (CANCELADA/FINALIZADA). La integración HTTP de 6.7 y la conversión temporal UTC de 6.8 están implementadas y probadas; las verificaciones MySQL de integración usan esquemas temporales.

## Diferencias observadas en la implementación actual

Estado contrastado con el código y la ejecución MySQL temporal del 2026-11-08:

- The contract uses integer `fila`. Java entities, initialization/availability DTOs, service keys and repository parameters now use `Integer`; V1 remains unchanged and creates legacy `VARCHAR(255)` columns. New Flyway migration V4 validates values and composite-key collisions before converting both columns to `INT`, preserving nullability and named constraints. Each environment still needs a read-only inventory before V4 is applied; incompatible values cause V4 to stop before DDL and require an approved remediation.
- `numero` is `Integer` and the numbered-seat unique key is present in the entity/schema. External event, sector and user identifiers are scalar Strings; the local reservation/detail association is separate from cross-service references.
- `EstadoEvento` y `ventaSuspendida` representan el estado local de ciclo de vida y suspensión; `EstadoVentaService` mapea el contrato HTTP. Los controladores exponen inicialización, consulta de disponibilidad, estado de venta, creación/consulta de reservas, confirmación y liberación.
- Los servicios implementan también vencimiento, devoluciones parciales y Outbox. La verificación de extremo a extremo del Gateway está fuera de este repositorio.
- El límite se establece durante la inicialización y no existe una operación HTTP para modificarlo posteriormente. Las pruebas MySQL verifican su aplicación sobre reservas y ventas, concurrencia e independencia por cliente/evento.
- `X-User-Id` se recibe como encabezado requerido para crear reservas y determina el titular; `DisponibilidadHttpTests.reservationRequiresUserHeaderAndRejectsIdentifiersLongerThan255WithCommonBadRequest` verifica su ausencia y que no haya escrituras. La propagación del Gateway real sigue fuera del repositorio.
- La prueba MySQL `reservationExpiryRoundTripsAsTheSameUtcInstantInMySql` confirmó la conservación del instante UTC al persistir y recuperar `expiraEn`.
- El 2026-11-08 se ejecutaron 45 pruebas MySQL temporales (5 de migración, 39 de concurrencia y 1 de backfill legacy), todas aprobadas. Flyway aplicó V1–V6 y Hibernate `ddl-auto=validate` pasó en MySQL 8.4.13. `./mvnw test` ejecutó 188 pruebas: 143 aprobadas y 45 omitidas por requerir explícitamente la integración MySQL.
- `mysqlReservationRetriesAfterAReproducibleDeadlockWithoutDuplicateEffects` induce un ciclo real de locks. La transacción blocker obtiene el lock del evento después de que MySQL resuelve el ciclo; un spy verifica que la consulta de idempotencia de la reserva se repite, y la creación termina con una única reserva/detalle y cantidades consistentes. La prueba verifica la recuperación observable, pero no captura directamente el SQLState 1213/40001.
- MS2 no exige `X-User-Role`; el Gateway gestiona autorización y transmite los valores compartidos acordados. La verificación del Gateway externo a este repositorio sigue pendiente.

## Risks / Trade-offs

- El cambio de `fila` String a Integer está implementado en Java y V4. Aplicar V4 requiere inventariar cada entorno; etiquetas no numéricas, valores fuera de rango y colisiones detienen la migración sin conversión automática. La coordinación de despliegue con MS1/MS3 sigue siendo necesaria.
- Reintentos de pagos/devoluciones atraviesan servicios distintos; MS2 necesita transiciones idempotentes y MS3 debe coordinar compensaciones.
- Cambios de estado de venta deben bloquear nuevas reservas sin alterar holds durante suspensión.
- Notificaciones de disponibilidad pueden repetirse por fallas de red; MS1 ya define la respuesta de procesamiento (`204`, `404` o `409` según el estado del evento). El Outbox durable, el reintento y la recuperación de leases están implementados; la suite MySQL verifica la ruta funcional, mientras que la garantía frente a toda falla de red/reinicio sigue siendo un riesgo operacional a observar.

## Migration Plan

No modificar migraciones aplicadas. V4 convierte `fila` a `INT` en ambas tablas solo después de validar que los valores son enteros dentro del rango de Java `Integer` y que la clave única compuesta no colisionará. Las filas no numéricas, fuera de rango y las colisiones detienen la migración antes de cualquier DDL. V4 conserva la nulabilidad, la clave única, el CHECK de detalles y las demás claves/restricciones; amplía `huella_inicializacion` a 67 caracteres para el prefijo versionado. El inventario de cada entorno y la remediación de datos incompatibles siguen siendo precondiciones de aplicación. Hibernate valida el esquema; no lo modifica automáticamente.
