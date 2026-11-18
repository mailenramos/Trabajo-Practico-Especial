# Design

## Contexto y evidencia del código actual

El aviso de cancelación ya tiene una implementación persistente, pero está acoplada al documento y al ciclo de vida de `Evento`:

| Código existente | Uso actual | Reutilización en el cambio |
|---|---|---|
| `domain/Evento.java` (`avisoCancelacionPendiente`, `cancelar`, `marcarAvisoCancelacionEntregado`) | Guarda el pendiente junto con motivo y fecha de cancelación. | Conservar el cambio de dominio de cancelación; trasladar el estado de entrega a la outbox y retirar el flag una vez migrados los pendientes históricos. |
| `service/EventoService.java` (`cancelarLocal`, `buscarAvisosCancelacionPendientes`, `marcarAvisoCancelacionEntregado`) | Persiste la cancelación, busca pendientes y los marca entregados. | Reutilizar la lógica de modificación del evento; reemplazar las operaciones de búsqueda/marcado por el registro y las transiciones genéricas de outbox. |
| `repository/EventoRepository.java` | Busca eventos con el flag de aviso pendiente. | Sustituir esa consulta por un repositorio dedicado a avisos y consultas atómicas por estado/fecha. |
| `service/EventoLifecycleService.java` | Confirma primero el cambio de venta en Reservas, persiste la cancelación, intenta avisar a Compras y captura fallos para no revertir la cancelación. | Mantener el orden de negocio y el intento inmediato posterior al commit, delegando entrega y reintentos al dispatcher común. |
| `compras/http/EventoCancelacionReenvioScheduler.java` y `GestionLugaresApplication` (`@EnableScheduling`) | Ejecuta el reenvío dedicado con `eventos.cancelacion.reenvio-interval`. | Reemplazar el scheduler específico por uno general que procese la colección outbox; conservar la infraestructura de Spring Scheduling. |
| `compras/ComprasClient.java`, `compras/http/HttpComprasClient.java`, `compras/EventoCanceladoRequest.java` | Envía `POST /eventos-cancelados`; considera `200` y `202` respuestas aceptadas. | Reutilizar destino, cliente HTTP y payload existente como adaptador del tipo de aviso de cancelación. |
| `compras/http/ComprasHttpConfiguration.java` y `ComprasProperties.java` | Configura RestClient con timeout de conexión de 2 s y lectura de 5 s. | Reutilizar los timeouts actuales al entregar por HTTP. |
| `ComprasCancellationFlowTest`, `HttpComprasClientTest`, `EventoRepositoryMongoTest` | Cubre cancelación, aviso aceptado/fallido, reintento y persistencia. | Extenderlo para estados outbox, atomicidad, reentregas y concurrencia; conservar la verificación del contrato actual de cancelación. |
| Pruebas Mongo con `MongoDBContainer#getReplicaSetUrl()` | Inician un MongoDB de Testcontainers con replica set. | Reutilizar ese patrón para probar transacciones e índices; no cambiar Compose. |
| `application.yml` | La URI predeterminada ya incluye `replicaSet=rs0`. | Mantener la precondición existente y agregar propiedades configurables para el dispatcher/reintentos. |

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

Los documentos de eventos con `avisoCancelacionPendiente=true` deben convertirse en avisos outbox antes de retirar ese campo y el scheduler dedicado. La conversión debe ser repetible sin crear avisos duplicados.

### Selección y entrega

Un servicio común tomará un aviso atómicamente mediante `findAndModify`, pasando de `PENDIENTE` a `PROCESANDO` y registrando un lease de 60 s. Sólo una instancia podrá reclamar el mismo aviso vigente. Cada intento HTTP llevará el ID estable en `X-Aviso-Id`, sin modificar el payload. El aviso se entrega por HTTP con timeouts de conexión de 2 s y lectura de 5 s.

- Un `200` o `202` válido de Compras significa que el destino aceptó o ya conoce la recepción. El outbox pasa a `ENTREGADO`; esto no afirma que el destino haya terminado el procesamiento de negocio.
- Los errores reintentables se registran con espera progresiva hasta el límite de intentos; errores definitivos o agotar el límite pasan a `ERROR_FINAL` y no se reintentan automáticamente.
- Un aviso `PROCESANDO` cuyo lease venció puede recuperarse atómicamente si aún cumple el límite. La entrega repetida conserva el mismo ID para permitir deduplicación en destino.

El envío inmediato posterior a la transacción se realiza por el mismo camino de claim/entrega que usa el Scheduler, evitando una segunda implementación de HTTP o de manejo de estado. Se conserva `@EnableScheduling`; el intervalo, límite de intentos y esperas serán configurables. El intervalo actual `eventos.cancelacion.reenvio-interval` se conserva como propiedad del dispatcher general, con su valor predeterminado actual de 60 s.

## Transición y compatibilidad

1. Añadir el manager transaccional y la colección outbox sin tocar Compose; las pruebas de integración usan `MongoDBContainer#getReplicaSetUrl()`.
2. Migrar avisos legacy pendientes de eventos a outbox de manera idempotente.
3. Cambiar la cancelación para guardar evento y outbox en una transacción; conservar el intento inmediato, `200/202` como recepción aceptada y `200 OK` al administrador aunque Compras falle.
4. Activar el dispatcher general y retirar el uso del booleano y del scheduler exclusivos cuando no queden pendientes legacy.

## Decisiones

1. **Clasificación de errores:** timeouts, errores de conexión, respuestas `5xx`, `408` y `429` son reintentables. El resto de respuestas `4xx` es definitivo; todo error definitivo deja el aviso en `ERROR_FINAL`.
2. **Reintentos:** máximo 8 intentos, contados al reclamar el aviso. La espera comienza en 30 s, se duplica por intento, tiene un tope de 15 min y no agrega jitter. El intervalo de polling conserva la configuración existente `eventos.cancelacion.reenvio-interval` (60 s por defecto), generalizada al dispatcher.
3. **Lease:** un aviso reclamado pasa a `PROCESANDO` por 60 s. Al vencer, puede volver a reclamarse; el reclamo cuenta como un intento, incluso si la instancia cae antes de enviar HTTP.
4. **Idempotencia HTTP:** cada entrega envía el ID en el header `X-Aviso-Id` sin cambiar el payload. MS3 puede ignorar ese header y continúa deduplicando cancelaciones por `eventoId`.
5. **ID y backfill:** el ID de cancelación es determinístico (`cancelacion-{eventoId}`) y tiene índice único. El mismo ID permite que el backfill de flags legacy sea idempotente.
6. **Fallo local tras confirmar Reservas:** se acepta la divergencia temporal si falla la transacción Mongo posterior a la confirmación remota. Reintentar la cancelación vuelve a resolver el estado de Reservas y permite completar el cambio local.
7. **Revisión de errores finales:** `ERROR_FINAL` se revisa directamente en MongoDB; este cambio no agrega endpoints operativos.

## Preguntas abiertas

1. ¿Qué respuestas HTTP y errores de transporte son definitivos y cuáles reintentables? En particular, ¿cómo deben tratarse los `4xx`, `5xx`, timeouts y respuestas no contempladas?
2. ¿Cuáles son los valores predeterminados y los límites configurables de intentos y espera progresiva? ¿La progresión requiere máximo, factor o jitter?
3. ¿Cuál es la duración del lease `PROCESANDO` y cómo se cuenta un intento si la instancia cae después del claim pero antes de enviar HTTP?
4. ¿Cómo se comunica `id` al destino: header de idempotencia, campo del payload u otro contrato? Compras actualmente deduplica `POST /eventos-cancelados` por `eventoId`; ¿debe ampliarse MS3 para deduplicar por ID de aviso?
5. ¿Qué estrategia de ID único permite backfill idempotente de avisos legacy y qué política se usa si hay datos legacy inconsistentes?
6. Si Reservas confirma la cancelación y la transacción Mongo posterior falla, ¿se acepta la posible divergencia temporal o se requiere una acción de reconciliación?
7. ¿Se necesita un mecanismo operativo para consultar/reprocesar manualmente `ERROR_FINAL`, o basta con persistirlo para revisión directa en MongoDB?
