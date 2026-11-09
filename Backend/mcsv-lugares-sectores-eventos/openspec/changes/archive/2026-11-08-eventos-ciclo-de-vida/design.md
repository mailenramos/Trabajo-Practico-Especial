# Design

## Context

El servicio ya persiste lugares y sectores en MongoDB; los lugares contienen sectores embebidos con IDs de sector String. Los endpoints públicos llegan por API Gateway y reciben `X-User-Id` y `X-User-Role`. Ver `specs/eventos/spec.md` para el comportamiento observable.

Los errores HTTP de todos los servicios usan `{"timestamp":"...","status":400,"error":"Bad Request","mensaje":"...","campos":[{"campo":"...","mensaje":"..."}]}`. `campos` se omite cuando no aplica. El puerto local por defecto de este servicio será `8081`; la variable `PORT` puede sobrescribirlo.

## Goals / Non-Goals

**Goals:**
- Guardar eventos, configuración de sectores y precios en MongoDB con identidad compatible con lugares y Reservas.
- Mantener coherencia al publicar y al procesar transiciones concurrentes.
- Aislar cada contrato externo detrás de su interfaz y un cliente por servicio, simulables en pruebas.

**Non-Goals:**
- Implementar búsqueda/detalle público con disponibilidad (F3), compras o reservas.
- Exponer endpoints internos de integración a través del API Gateway.
- Persistir asientos individuales o disponibilidad en este servicio.

## Decisions

### Persistencia y documento del evento

Usar la colección `eventos`, un documento por evento y sectores/precios embebidos en ese documento. Todos los IDs se representan como String en API y dominio: el `_id` de evento se almacena como ObjectId y se serializa en hexadecimal; `lugarId`, `organizadorId` y `sectorId` son String. Cada sector conserva el UUID inmutable que lo identifica en `lugares`.

Al crear un evento se valida el lugar leyendo la colección local `lugares`, sin llamada a otro servicio. Se copia `ciudad` del lugar y la configuración de sus sectores. Cada precio lleva también una copia del nombre del sector, para responder a Compras sin cruzar colecciones. Los precios y `limiteEntradasPorCliente` son inmutables luego de publicar. El límite es entero >= 1; si no se especifica al crear o durante el borrador, vale 4.

Ejemplo representativo del documento:

```json
{
  "id": "672a1f77bcf86cd799439011",
  "organizadorId": "organizador-42",
  "lugarId": "507f1f77bcf86cd799439011",
  "ciudad": "Buenos Aires",
  "nombre": "Festival Central",
  "descripcion": "Música en vivo",
  "tipo": "MUSICA",
  "participantes": ["Artista A", "Artista B"],
  "fechaHora": "2027-05-16T00:00:00Z",
  "estado": "borrador",
  "ventaSuspendida": false,
  "limiteEntradasPorCliente": 4,
  "sectores": [
    {
      "sectorId": "a3d53e8e-9f53-4e8b-997a-a45c6583b2f1",
      "nombre": "Platea",
      "tipo": "NUMERADO",
      "filas": 1,
      "asientosPorFila": 2,
      "capacidad": null,
      "precio": {"$numberDecimal": "15000.00"}
    },
    {
      "sectorId": "ea248a93-5198-4c49-8a1a-d8c647db9bf2",
      "nombre": "Campo",
      "tipo": "NO_NUMERADO",
      "filas": null,
      "asientosPorFila": null,
      "capacidad": 500,
      "precio": null
    }
  ],
  "precioMinimo": null,
  "version": 0
}
```

Los IDs son Strings en los contratos; el precio usa `BigDecimal`/MongoDB `Decimal128`, nunca `double`. `precioMinimo` se calcula con los precios de todos los sectores al publicar. El evento tiene solo fecha/hora de inicio (`fechaHora`), en UTC al interoperar; por el supuesto de este cambio, se considera finalizado cuando esa fecha/hora ya pasó. Esto reemplaza la fecha/hora de finalización mencionada en la historia original.

Los asientos no se persisten. Para Reservas, los asientos numerados se derivan de filas × asientosPorFila y se envían como lista de pares `(fila, numero)` con rangos desde 1; `capacidadTotal` es la cantidad de pares. Reservas identifica cada asiento por `(sectorId, fila, numero)`. Los sectores no numerados envían capacidad y una lista `asientos` vacía.

### Consistencia, publicación y cambios de estado

Usar actualización condicional por estado y/o `@Version` de Spring Data MongoDB. Si otra escritura cambió la versión o el estado esperado, no sobrescribirla: responder `409 Conflict` cuando corresponda. Cada operación muta un solo documento de evento; no se usan transacciones multi-documento.

Al publicar, llamar primero a Reservas para inicializar la disponibilidad. Solo ante `204 No Content` se cambia condicionalmente el estado local de `borrador` a `publicado` y se calcula `precioMinimo`. Si hay timeout, error 5xx o `409` por datos de inicialización distintos, el evento sigue en borrador y se devuelve `503 Service Unavailable` con el formato estándar. El reintento repite el mismo PUT y el mismo cuerpo; Reservas responde 204 sin duplicar la inicialización. Reservas `400` indica un error de contrato/datos que se debe registrar para corrección; no se publica el evento.

Para suspender, reactivar, cancelar y finalizar, invocar primero a Reservas con el estado de venta correspondiente; cambiar el estado local solo después de `204`. Ante timeout o cualquier respuesta de error, mantener el estado local y devolver `503` para permitir reintento. La finalización automática también se reintenta si Reservas no confirma. La suspensión se guarda en `ventaSuspendida`, independiente del estado formal. La notificación a Compras es distinta: al cancelar se guarda el evento cancelado junto con la notificación pendiente, y un fallo de Compras no revierte la cancelación.

La recepción de disponibilidad solo permite `publicado -> agotado` y `agotado -> publicado`. Un aviso duplicado devuelve 204. Avisos tardíos para evento cancelado o finalizado responden 204 sin cambios; para evento en borrador responden 409; evento inexistente responde 404.

### Contratos con otros servicios

Las llamadas son REST directo en JSON, sin API Gateway ni autenticación entre servicios. Las bases URL y los timeouts son configurables. Cada servicio externo se invoca detrás de una interfaz propia (`ReservasClient`, `ComprasClient`) y un adaptador HTTP que concentra rutas, serialización, timeouts y traducción de respuestas; en pruebas se reemplazan las interfaces por clientes simulados.

#### A) Disponibilidad y Reservas — contrato confirmado

| Quién llama | Cuándo | Endpoint | Body | Respuestas |
|---|---|---|---|---|
| Este servicio -> Reservas | Inicializar disponibilidad al publicar | `PUT /disponibilidad/eventos/{eventoId}` | `{"limiteEntradasPorCliente":4,"sectores":[{"sectorId":"...","tipo":"NUMERADO","capacidadTotal":2,"asientos":[{"fila":1,"numero":1},{"fila":1,"numero":2}]},{"sectorId":"...","tipo":"NO_NUMERADO","capacidadTotal":500,"asientos":[]}]}` | `204` inicializado o reintento con mismos datos; `400`; `409` si difieren; `500`. Ante timeout, 5xx o 409, el evento permanece borrador y se responde `503`. |
| Este servicio -> Reservas | Suspender/reactivar/cancelar/finalizar venta | `PUT /disponibilidad/eventos/{eventoId}/venta` | `{"estado":"ACTIVA"}` (también `SUSPENDIDA`, `CANCELADA`, `FINALIZADA`) | `204`; `400`; `404` no inicializado; `409` incompatible; `500`. Este servicio llama antes de cambiar su estado local; si falla, responde `503` y no modifica el estado. |
| Este servicio -> Reservas | Consultar disponibilidad para detalle US-3.3 de `busqueda-eventos` | `GET /disponibilidad/eventos/{eventoId}` | Sin body | `200` con disponibilidad; `404`; `500`. Si Reservas falla, el detalle devuelve disponibilidad `null`. Esta ruta se documenta aquí, no se implementa en este cambio. |
| Reservas -> este servicio | Agotado/recuperación | `PUT /eventos/{eventoId}/disponibilidad` | `{"agotado":true}` o `{"agotado":false}` | `204` al aplicar, duplicado o aviso tardío cancelado/finalizado; `404` inexistente; `409` si está en borrador. Sin headers de usuario ni Gateway. |

El body de inicialización siempre incluye `limiteEntradasPorCliente` y todos los sectores. Para cada sector numerado se construye la lista de asientos derivada de filas × asientosPorFila; no se persiste localmente. Reservas identifica cada asiento mediante `(sectorId, fila, numero)`. `fila` se envía como entero y repetir el mismo estado de venta devuelve `204`.

#### B) Compras y Devoluciones — contrato confirmado

Las llamadas de Compras y Devoluciones también son directas, en JSON y sin headers de usuario ni Gateway.

| Quién llama | Cuándo | Endpoint | Body | Respuestas |
|---|---|---|---|---|
| Compras -> este servicio | Consultar evento para compras | `GET /internal/eventos/{eventoId}` | Sin body | `200` en cualquier estado; `404` si no existe. Sin headers de usuario y fuera del Gateway. |
| Este servicio -> Compras | Avisar cancelación | `POST /eventos-cancelados` | `{"eventoId":"...","motivo":"...","canceladoEn":"2026-11-06T18:00:00Z"}` | `202` aceptado; `200` si ya estaba procesado; ambos son éxito idempotente. |

La base URL de Compras se configura con `compras.base-url` (por defecto `http://localhost:8083`), `compras.connect-timeout` (2 s) y `compras.read-timeout` (5 s). La llamada al aviso ocurre después de que Reservas confirma la venta cancelada y el documento local pasa a `cancelado`. Si Compras falla, no responde o devuelve error, la cancelación se conserva y el administrador recibe `200`; el aviso queda pendiente para reenvío automático. El intervalo se configura con `eventos.cancelacion.reenvio-interval` (por ejemplo `60s`). Los reintentos son seguros porque Compras trata el aviso de forma idempotente.

El aviso pendiente se persiste junto con los datos de cancelación en el documento del evento: estado de entrega pendiente, motivo y `canceladoEn`. El proceso programado consulta documentos cancelados con aviso pendiente y los reenvía; ante `200` o `202`, actualiza el estado de entrega para no volver a enviar. Si el evento ya estaba cancelado, se responde `409` y no se crea un segundo aviso.

El GET interno devuelve `{"eventoId":"...","nombre":"...","fechaInicio":"2026-12-01T21:00:00Z","estado":"publicado","sectores":[{"sectorId":"...","nombre":"Platea","precio":15000.00}]}`. Devuelve cualquier estado; `estado` usa minúsculas, `fechaInicio` serializa `fechaHora` como ISO-8601 UTC, y `precio` es decimal basado en BigDecimal/Decimal128 o `null` si el sector aún no tiene precio. Al definir el precio, el evento guarda una copia del nombre del sector para responder sin cruzar colecciones.

El endpoint de cancelación acepta body opcional `{"motivo":"..."}`. Si se omite el body o el motivo, se usa un texto por defecto, por ejemplo `Cancelación del evento por el administrador`. `canceladoEn` registra el instante UTC efectivo de cancelación.

### Configuración y red local

Configurar `server.port=${PORT:8081}` para evitar colisión con Reservas en `8080`. Configurar `reservas.base-url` (por defecto `http://localhost:8080`) y `compras.base-url` (por defecto `http://localhost:8083`). Los timeouts de ambos clientes son configurables.

Los timeouts del cliente de Reservas se configuran con `reservas.connect-timeout` (2 s por defecto) y `reservas.read-timeout` (5 s por defecto). Las operaciones de ciclo de vida se exponen como `PUT /eventos/{eventoId}/publicar`, `PUT /eventos/{eventoId}/venta/suspender`, `PUT /eventos/{eventoId}/venta/reactivar` y `PUT /eventos/{eventoId}/cancelar`. El callback `PUT /eventos/{eventoId}/disponibilidad` es interno, no requiere encabezados de usuario y queda fuera del Gateway.

## Risks / Trade-offs

- [Un aviso tardío puede competir con una cancelación o finalización] → aplicar estado/versionado condicional e ignorar avisos tardíos con 204 como define el contrato.
- [La copia de ciudad y sectores puede quedar desactualizada ante cambios del lugar] → conservar instantánea al crear evento; revisar con el grupo si lugares y sectores serán inmutables al estar referenciados.
- [Una falla de Reservas bloquea el cambio local de venta/estado] → responder 503 sin mutar el estado y permitir reintento.
- [Compras puede no estar disponible después de una cancelación] → persistir el aviso pendiente y reintentarlo automáticamente; la cancelación local no se revierte.

## Migration Plan

No hay migración de datos: se incorpora una colección nueva. Configurar el puerto 8081, desplegar la colección e índices y las propiedades de clientes. Agregar al Gateway solo rutas públicas explícitas; mantener los callbacks y la ruta interna de Compras fuera del Gateway. La reversión consiste en retirar el enrutamiento nuevo y volver a la versión anterior; no borrar documentos de eventos automáticamente.

## Open Questions

No quedan preguntas abiertas sobre los contratos con Reservas y Compras, ya confirmados. Los timeouts pueden ajustarse por ambiente mediante sus propiedades de configuración.
