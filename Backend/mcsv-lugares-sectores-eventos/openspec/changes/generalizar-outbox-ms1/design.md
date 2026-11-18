# Design

## Contexto y evidencia del código actual

El aviso de cancelación ya tiene una implementación persistente, pero está acoplada al documento y al ciclo de vida de `Evento`:

| Código existente antes de este cambio | Uso anterior | Reutilización o transición |
|---|---|---|
| `domain/Evento.java` (`avisoCancelacionPendiente`, `cancelar`, `marcarAvisoCancelacionEntregado`) | Guardaba el pendiente junto con motivo y fecha de cancelación. | El flag y sus métodos se retiraron; el backfill transitorio migra documentos legacy a outbox. Se conserva la información de cancelación del dominio. |
| `service/EventoService.java` (`cancelarLocal`, `buscarAvisosCancelacionPendientes`, `marcarAvisoCancelacionEntregado`) | Persistía la cancelación, buscaba pendientes y los marcaba entregados. | Se conserva la lógica local de cancelación; la búsqueda y el seguimiento de entrega ahora pertenecen a los servicios generales de outbox. |
| `repository/EventoRepository.java` | Buscaba eventos con el flag de aviso pendiente. | La consulta legacy se retiró; el repositorio outbox selecciona avisos por estado y fecha. |
| `service/EventoLifecycleService.java` | Confirmaba primero el cambio de venta en Reservas, persistía la cancelación e intentaba avisar a Compras. | Conserva el orden de negocio y el intento inmediato posterior al commit, delegando entrega y reintentos al dispatcher común. |
| `compras/http/EventoCancelacionReenvioScheduler.java` y `GestionLugaresApplication` (`@EnableScheduling`) | Ejecutaba el reenvío dedicado de cancelaciones. | El scheduler dedicado se retiró; `OutboxScheduler` procesa avisos generales y se conserva la infraestructura Spring Scheduling. |
| `compras/ComprasClient.java`, `compras/http/HttpComprasClient.java`, `compras/http/ComprasHttpConfiguration.java` y `HttpComprasClientTest` | Cliente HTTP dedicado y sus pruebas para notificar a Compras. | Se retiraron al migrar la entrega al dispatcher genérico. Se conservan `compras/EventoCanceladoRequest.java` para el payload y `compras/http/ComprasProperties.java` para la URL base; `OutboxConfiguration` configura el HTTP genérico. |
| `ComprasCancellationFlowTest`, `EventoCancelacionOutboxIntegrationTest`, pruebas de dispatcher/repositorio outbox y `EventoRepositoryMongoTest` | Cubrían el flujo anterior de cancelación y persistencia. | Cubren la transacción, el intento inmediato, la entrega y los reintentos generales, los índices, la recuperación y el backfill. |
| Pruebas Mongo con `MongoDBContainer#getReplicaSetUrl()` | Inician un MongoDB de Testcontainers con replica set. | Reutilizar ese patrón para probar transacciones e índices; no cambiar Compose. |
| `application.yml` | La URI predeterminada incluye `replicaSet=rs0`. | Se conserva esa precondición y se configura `outbox.dispatcher.interval`; máximo de intentos, backoff y lease son valores fijos. |

La inspección no encontró un `MongoTransactionManager`, métodos `@Transactional` ni una colección outbox existente en MS1. `MongoDecimalConfiguration` configura conversiones de Mongo, no transacciones. El endpoint de Compras se deduplica actualmente por `eventoId`; su DTO no transporta un ID independiente del aviso.

## Objetivos y fuera de alcance

**Objetivos**

- Persistir atómicamente cada cambio de evento y el aviso que ese cambio origina.
- Proveer una outbox genérica con estados y entrega segura entre varias instancias.
- Migrar el aviso de cancelación a Compras conservando endpoint, payload funcional, respuesta al administrador y reintento automático.
- Dejar la infraestructura lista para que cambios posteriores agreguen productores de notificaciones.

**Fuera de alcance de este cambio**

- Producir avisos para publicación a Preferencias/recomendaciones o para modificaciones de evento hacia Compras.
- Cambiar la configuración de Docker Compose: el entorno ya debe ofrecer MongoDB replica set `rs0`.
- Incorporar transacciones distribuidas entre MS1 y otros servicios.
- Definir o implementar el procesamiento posterior de una notificación en el destino.

## Diseño propuesto

### Documento outbox

Crear la colección `outbox`. Cada documento tendrá al menos:

| Campo lógico | Propósito |
|---|---|
| `id` | Identificador estable del aviso, sin cambiar entre intentos. |
| `tipo` | Tipo de evento/notificación. El aviso actual corresponde a cancelación; los tipos futuros se agregan con sus productores. |
| `eventoId` | Evento que originó el aviso. |
| `destino` | Servicio y recurso HTTP de destino. |
| `payload` | Datos serializables necesarios para entregar el aviso. |
| `estado` | `PENDIENTE`, `PROCESANDO`, `ENTREGADO` o `ERROR_FINAL`. |
| `intentos` | Cantidad de intentos de entrega contabilizados. |
| `proximoIntento` | Momento a partir del cual puede reintentarse. |
| `ultimoError` | Diagnóstico del último fallo, si lo hubo. |
| Fechas | Creación/actualización y marcas necesarias para entrega y recuperación de leases vencidos. |

El ID será `cancelacion-{eventoId}` para este productor y tendrá un índice único. Un índice compuesto permitirá buscar pendientes vencidos y recuperar procesos vencidos.

### Transacción y producción

Configurar un `MongoTransactionManager` sobre el mismo `MongoDatabaseFactory` usado por repositorios y `MongoTemplate`. La operación local que produce un aviso guardará el cambio de `Evento` y el documento outbox en una sola transacción MongoDB. El aviso de cancelación es el único productor implementado por este cambio. Productores futuros —publicación y modificaciones de fecha, horario o lugar— deberán usar la misma frontera transaccional cuando se incorporen.

Las llamadas HTTP a Reservas y Compras quedan fuera de la transacción MongoDB. Para la cancelación se conserva el orden actual: confirmar primero la cancelación de venta en Reservas; luego guardar estado local y aviso en una transacción; después del commit intentar la entrega inmediata mediante el dispatcher genérico. El Scheduler procesa los avisos que sigan pendientes. Un error del destino no cambia la cancelación ni su respuesta exitosa al usuario.

Un fallo al escribir el aviso o el evento hace rollback de ambos cambios MongoDB. Esto no revierte una confirmación HTTP que Reservas haya dado antes de iniciar la transacción; no se introduce una transacción distribuida.

Los documentos legacy con `avisoCancelacionPendiente=true` se convierten al iniciar en avisos outbox mediante un backfill repetible. El flag y el scheduler dedicado ya se retiraron; los documentos existentes se leen sin problema y el backfill elimina el campo después de insertar o encontrar el aviso.

### Selección y entrega

Un servicio común tomará un aviso atómicamente mediante `findAndModify`, pasando de `PENDIENTE` a `PROCESANDO` y registrando un lease de 60 s. Sólo una instancia podrá reclamar el mismo aviso vigente. Cada intento HTTP usa `POST`, lleva el ID estable en `X-Aviso-Id` y no modifica el payload. El aviso se entrega por HTTP con timeouts de conexión de 2 s y lectura de 5 s.

- Cualquier respuesta `2xx` de Compras significa que el destino aceptó o ya conoce la recepción. El outbox pasa a `ENTREGADO`; esto no afirma que el destino haya terminado el procesamiento de negocio.
- Timeouts, errores de conexión, `5xx`, `408` y `429` se reintentan con espera progresiva. El resto de `4xx` y las respuestas `3xx` son definitivos; los errores definitivos o agotar el límite pasan a `ERROR_FINAL` y no se reintentan automáticamente.
- Un aviso `PROCESANDO` cuyo lease venció puede recuperarse atómicamente si aún cumple el límite. La entrega repetida conserva el mismo ID para permitir deduplicación en destino.
- Al inicio de cada pasada, el scheduler barre avisos `PENDIENTE` que ya alcanzaron 8 intentos y avisos `PROCESANDO` cuyo lease venció y alcanzaron el límite. Los pasa a `ERROR_FINAL` con `ultimoError = "intentos agotados"` y limpia el lease antes de procesar avisos; no se entregan ni se vuelven a reclamar automáticamente.

El envío inmediato posterior a la transacción se realiza por el mismo camino de claim/entrega que usa el Scheduler, evitando una segunda implementación de HTTP o de manejo de estado. Se conserva `@EnableScheduling`. El intervalo se configura mediante `OUTBOX_DISPATCHER_INTERVAL` (`outbox.dispatcher.interval`), con default de 60 s. El máximo de 8 intentos, el backoff de 30 s duplicado por intento hasta 15 min, la ausencia de jitter y el lease de 60 s son valores fijos en el dispatcher.

## Transición y compatibilidad

1. Se agregó el manager transaccional y la colección outbox sin tocar Compose; las pruebas de integración usan `MongoDBContainer#getReplicaSetUrl()`.
2. Se migran avisos legacy pendientes de eventos a outbox de manera idempotente al iniciar.
3. La cancelación guarda evento y outbox en una transacción; conserva el intento inmediato, cualquier `2xx` como recepción aceptada y `200 OK` al administrador aunque Compras falle.
4. El dispatcher general reemplazó el uso del flag y del scheduler dedicados. El backfill al iniciar es el mecanismo transitorio para datos legacy restantes.

## Decisiones

1. **Clasificación de errores:** timeouts, errores de conexión, respuestas `5xx`, `408` y `429` son reintentables. El resto de respuestas `4xx` es definitivo; todo error definitivo deja el aviso en `ERROR_FINAL`.
2. **Reintentos:** máximo 8 intentos, contados al reclamar el aviso. La espera comienza en 30 s, se duplica por intento, tiene un tope de 15 min y no agrega jitter. El intervalo de polling se configura con `OUTBOX_DISPATCHER_INTERVAL` (60 s por defecto); el límite y la espera progresiva son constantes, no propiedades externas.
3. **Lease:** un aviso reclamado pasa a `PROCESANDO` por 60 s. Al vencer, puede volver a reclamarse; el reclamo cuenta como un intento, incluso si la instancia cae antes de enviar HTTP.
4. **Idempotencia HTTP:** cada entrega envía el ID en el header `X-Aviso-Id` sin cambiar el payload. MS3 puede ignorar ese header y continúa deduplicando cancelaciones por `eventoId`.
5. **ID y backfill:** el ID de cancelación es determinístico (`cancelacion-{eventoId}`) y tiene índice único. El mismo ID permite que el backfill de flags legacy sea idempotente.
6. **Fallo local tras confirmar Reservas:** se acepta la divergencia temporal si falla la transacción Mongo posterior a la confirmación remota. Reintentar la cancelación vuelve a resolver el estado de Reservas y permite completar el cambio local.
7. **Revisión de errores finales:** `ERROR_FINAL` se revisa directamente en MongoDB; este cambio no agrega endpoints operativos.
