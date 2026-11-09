## Why

MS2 es responsable de la disponibilidad, las reservas temporales y las transiciones entre reserva y venta de TicketYa. Este cambio define sus límites, datos persistentes e integraciones REST con MS1 y MS3 para impedir dobles ventas y mantener cantidades consistentes.

## What Changes

- Inicializar atómicamente la disponibilidad recibida de MS1 y permitir reintentos idénticos sin duplicar datos; rechazar contenido distinto para un evento inicializado.
- Consultar disponibilidad para MS1, diferenciando inventario numerado y no numerado.
- Recibir de MS1 el estado de venta `ACTIVA`, `SUSPENDIDA`, `CANCELADA` o `FINALIZADA`; la tarea 2.5 cubre publicación/activación, suspensión y reanudación, y la tarea 4.4 cubre cancelación/finalización con liberación de reservas temporales vigentes.
- Representar el ciclo de vida mediante los estados existentes `PENDIENTE_PUBLICACION`, `PUBLICADO`, `CANCELADO` y `FINALIZADO`; `AGOTADO` es una condición derivada de las ventas confirmadas, no un reemplazo del ciclo de vida. El modelo local existente (`EstadoEvento` y `ventaSuspendida`) conserva estas dimensiones mediante el cálculo de agotamiento antes y después de cada transición de ventas, sin inventar estados ni campos persistidos.
- Crear reservas temporales completas o rechazarlas sin asignación parcial, con duración fija y no configurable de exactamente 10 minutos e idempotencia mediante `solicitudId`.
- Consultar, confirmar y liberar reservas para MS3, incluida la liberación parcial idempotente por `devolucionId`, un UUID global generado por MS3 y usado por MS2 exclusivamente como clave de idempotencia.
- Mantener transaccionalmente inventario, reservas y cantidades por cliente/evento, actualizándolas exactamente una vez en creación, confirmación, liberación, vencimiento y devolución; notificar a MS1 solo al cambiar la condición de agotado/disponible. Un cero de `cantidadDisponible` debido a reservas temporales no significa agotamiento.
- Aplicar el límite positivo recibido de MS1 para cada evento. Si el organizador no configura un valor, MS1 envía 4. El límite no cambia después de publicar y no se agrega una operación de actualización.
- Usar `eventoId`, `sectorId` y `clienteId` como String. `fila` y `numero` son Integer. El lugar numerado se identifica exclusivamente por `(eventoId, sectorId, fila, numero)`; no existe `lugarId` ni `asientoId` como identificador de negocio.
- `sectorId` es único dentro del evento y cada sector numerado tiene pares `(fila, numero)` únicos. La igualdad entre `capacidadTotal` y la longitud de la lista completa de asientos todavía requiere confirmación.
- La clave de idempotencia de solicitud es `(clienteId, solicitudId)`; la comparación de contenido incluye además el `eventoId` de la URL y el body.
- Una devolución parcial solo puede liberar detalles de la reserva confirmada indicada. Un detalle ya devuelto no se libera ni se descuenta de cantidades otra vez bajo otro `devolucionId`.
- Mantener comunicación interna por REST/JSON directa, fuera del API Gateway y sin autenticación entre microservicios. Las operaciones de clientes pasan por el Gateway.
- Documentar la notificación MS2→MS1 de agotamiento/restauración y las respuestas de MS1: `204` al procesar, incluso si el valor se repite o llega tarde para evento cancelado/finalizado; `404` si el evento no existe; `409` únicamente mientras esté en borrador.
- Fijar respuestas conocidas: inicialización `204` nueva/reintento idéntico; consulta de disponibilidad y consulta de reserva `200`; nueva reserva `201`; reintento idéntico vigente `200`; conflicto por contenido distinto o reserva original no vigente `409`; confirmación y liberación `204`; actualización del estado de venta `204` para transición correcta o repetición actual, `400` para estado inválido, `404` si MS2 no inicializó disponibilidad, `409` para transición incompatible y `500` para error interno. Otros errores comunes de contenido no soportado conservan `415`.
- Documentar errores con el formato y el catálogo de códigos acordados: `timestamp`, `status`, `error`, `mensaje` y `campos` opcional; cuando aparece, `campos` es una lista de objetos con exactamente `campo` y `mensaje`.

## Capabilities

### New Capabilities

- `availability-reservations`: inicialización y consulta de disponibilidad, reservas temporales, límites por evento, concurrencia, idempotencia, vencimiento, confirmación/liberación y notificaciones de agotamiento.

### Modified Capabilities

Ninguna. Este cambio introduce la especificación de MS2.

## Impact

- Persistencia propia de MS2: eventos, sectores, asientos, reservas, detalles y cantidades por cliente/evento; no se crean relaciones ni claves foráneas hacia otros microservicios.
- MS1 (puerto local `8081`) envía la inicialización y los cambios de estado; consulta disponibilidad y recibe notificaciones de agotamiento/restauración. MS2 usa el puerto local `8080`; MS3 (compras y devoluciones) usa el puerto local `8083` y consulta, confirma y libera reservas mediante REST directa.
- El cliente crea reservas a través del Gateway y MS2 identifica al titular mediante `X-User-Id`; la autorización corresponde al Gateway. MS3 consulta, confirma y libera reservas mediante REST directa. MS2 no exige `X-User-Role`.
- MS3 continúa siendo responsable de compras, devoluciones aprobadas y de coordinar con MS4 los pagos/reintegros y con MS5 la emisión o invalidación de entradas. MS2 solo recibe los cambios de estado del evento que le corresponden, impide nuevas reservas y libera reservas temporales vigentes cuando corresponda; no coordina ni ejecuta pagos, reintegros o invalidación de entradas.
- El formato de errores acordado es `timestamp`, `status`, `error`, `mensaje` y `campos` opcional, con el catálogo de códigos acordado.
- La propuesta describe el alcance; el código Java está implementado en el módulo MS2 y su evidencia se registra en la planificación técnica.

## Decisiones pendientes

- La consulta pública de compras/reservas propias corresponde a MS3 y está fuera del alcance de MS2; no es un pendiente de este servicio.
- Inventario del esquema de cada entorno antes de aplicar V4. V4 convierte únicamente valores enteros dentro de rango y sin colisiones; ante etiquetas, valores fuera de rango o colisiones se detiene antes del DDL. La remediación de esos datos incompatibles requiere aprobación y no se resuelve con una conversión inventada.
- Compatibilidad de reintentos para eventos que conservan huellas de inicialización antiguas sin versión; MS2 no los acepta como idénticos hasta que el equipo acuerde una estrategia verificable.

## Decisiones D1 y D5 acordadas

### D1 — Outbox sin versionado

Se conserva el Outbox actual y su mecanismo de ordenamiento por evento, sin agregar versionado. Se intenta respetar el orden de las notificaciones, pero no se garantiza un orden absoluto ante todos los escenarios de concurrencia, demoras y recuperación de mensajes. El token protege las actualizaciones locales; no impide por sí solo un envío HTTP tardío de un worker cuyo lease venció. Se mantienen la persistencia durable y los reintentos ante fallos de comunicación. El contrato de MS1 permanece `PUT /eventos/{eventoId}/disponibilidad` con únicamente `{"agotado": true|false}`; no se agrega `version` ni otro campo.

### D5 — Formato UUID, inexistencia e idempotencia

Para `reservaId` y `devolucionId`, un formato UUID inválido corresponde a `400 Bad Request` con `error: PARAMETRO_INVALIDO`. Un UUID válido cuyo recurso consultado o referenciado no existe corresponde a `404 Not Found`. Un `devolucionId` válido nuevo es una clave de idempotencia para una operación nueva, no una devolución que deba existir previamente: su ausencia en el ledger no produce 404 por sí sola. Se conserva `204` para el mismo `devolucionId`, la misma reserva y los mismos detalles, y `409 Conflict` para reutilización con otra reserva o detalles diferentes, sin repetir efectos. No se confunden formato inválido, recurso inexistente y conflicto de idempotencia.

### Estado de implementación D5

La implementación valida el formato UUID en las operaciones de reserva y devolución y mapea los errores de formato a `400 PARAMETRO_INVALIDO`. Los UUID válidos inexistentes conservan `404 Not Found`; una nueva clave de devolución no requiere existir previamente. Los contratos HTTP se verifican con pruebas aisladas. La precedencia entre múltiples errores simultáneos no se redefine.
