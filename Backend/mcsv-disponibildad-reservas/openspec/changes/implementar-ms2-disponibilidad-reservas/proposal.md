## Why

MS2 es responsable de la disponibilidad, las reservas temporales y las transiciones entre reserva y venta de TicketYa. Este cambio define sus límites, datos persistentes e integraciones REST con MS1 y MS3 para impedir dobles ventas y mantener cantidades consistentes.

## What Changes

- Inicializar atómicamente la disponibilidad recibida de MS1 y permitir reintentos idénticos sin duplicar datos; rechazar contenido distinto para un evento inicializado.
- Consultar disponibilidad para MS1, diferenciando inventario numerado y no numerado.
- Recibir de MS1 el estado de venta `ACTIVA`, `SUSPENDIDA`, `CANCELADA` o `FINALIZADA`; la tarea 2.5 cubre publicación/activación, suspensión y reanudación, y la tarea 4.4 cubre cancelación/finalización con liberación de reservas temporales vigentes.
- Representar el ciclo de vida mediante los estados existentes `PENDIENTE_PUBLICACION`, `PUBLICADO`, `CANCELADO` y `FINALIZADO`; `AGOTADO` es una condición derivada de las ventas confirmadas, no un reemplazo del ciclo de vida. Verificar si el modelo local existente (`EstadoEvento` y `ventaSuspendida`) puede mantener separadas estas dimensiones, sin inventar estados ni campos persistidos.
- Crear reservas temporales completas o rechazarlas sin asignación parcial, con duración fija y no configurable de exactamente 10 minutos e idempotencia mediante `solicitudId`.
- Consultar, confirmar y liberar reservas para MS3, incluida la liberación parcial idempotente por `devolucionId`, un UUID global generado por MS3 y usado por MS2 exclusivamente como clave de idempotencia.
- Mantener transaccionalmente inventario, reservas y cantidades por cliente/evento, actualizándolas exactamente una vez en creación, confirmación, liberación, vencimiento y devolución; notificar a MS1 solo al cambiar la condición de agotado/disponible. Un cero de `cantidadDisponible` debido a reservas temporales no significa agotamiento.
- Fijar el límite en 4 entradas por cliente y evento. MS1 envía siempre `limiteEntradasPorCliente: 4` y MS2 valida ese valor al inicializar; no es configurable por evento ni modificable posteriormente.
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
- No se implementa código Java en este artefacto.

## Decisiones pendientes

- Ruta y contrato de la consulta pública de una reserva propia por el cliente.
- Inventario del esquema de cada entorno antes de aplicar V4. V4 convierte únicamente valores enteros dentro de rango y sin colisiones; ante etiquetas, valores fuera de rango o colisiones se detiene antes del DDL. La remediación de esos datos incompatibles requiere aprobación y no se resuelve con una conversión inventada.
- Compatibilidad de reintentos para eventos que conservan huellas de inicialización antiguas sin versión; MS2 no los acepta como idénticos hasta que el equipo acuerde una estrategia verificable.
