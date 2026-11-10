## Why

Pagos (MS4) pasó sus rutas a internas (`/internal/...`) y dejó de atender las públicas `/api/v1/payments` y `/api/v1/refunds`. El cliente de Pagos de este servicio sigue llamando a las rutas viejas, fijas en el código, así que todo cobro, consulta de pagos por compra y reintegro fallaría contra el Pagos nuevo.

## What Changes

- El cliente de Pagos llama a las rutas internas:
  - Cobro: `POST /api/v1/payments` → `POST /internal/pagos` (con `Idempotency-Key`).
  - Pagos de una compra: `GET /api/v1/payments/purchase/{id}` → `GET /internal/pagos/compra/{purchaseId}` (sigue devolviendo una lista; 200 `[]` si no hay).
  - Reintegro: `POST /api/v1/payments/{id}/refunds` → `POST /internal/pagos/{paymentId}/reintegros` (con `Idempotency-Key`).
  - Consulta de reintegro: `GET /api/v1/refunds/{id}` → `GET /internal/reintegros/{refundId}`.
  - Reintento de reintegro: `POST /api/v1/refunds/{id}/retry` → `POST /internal/reintegros/{refundId}/reintentar`.
  - `GET /internal/pagos/{paymentId}` se documenta en el contrato; este servicio no lo usa (hoy tampoco usa la ruta vieja equivalente).
- Las cinco rutas que usa el cliente pasan a ser propiedades configurables (`servicios.pagos.rutas.*`), con las rutas internas como valor por defecto. Hoy están fijas en el código.
- Se asume que los cuerpos, los códigos de respuesta y el formato de error de Pagos no cambian. Queda como punto abierto a confirmar con Pagos.
- Se actualizan las pruebas del cliente REST, los comentarios del doble de prueba y del código que nombran `/retry` o `/payments/.../refunds`, `openapi.yaml`, `docs/historias-ms3.md` (contrato de Pagos y punto abierto) y los diagramas `docs/diagramas-ms3.md` y `docs/diagramas-ms3/secuencia-compra.mmd`.
- **BREAKING** (hacia Pagos): este servicio ya no funciona contra un Pagos que solo exponga las rutas `/api/v1/...`. Se puede volver a ellas por configuración, sin recompilar.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `pago-de-compra`: se agrega el requisito de cobrar y consultar los pagos de una compra por las rutas internas de Pagos, configurables.
- `reintegros`: se agrega el requisito de pedir, reintentar y consultar reintegros por las rutas internas de Pagos, configurables.

## Fuera de alcance

- Cambios en los cuerpos, códigos de respuesta o formato de error de Pagos: si Pagos los cambia, será otro cambio.
- Usar `GET /internal/pagos/{paymentId}` (no hay un caso que lo necesite).
- Rutas de Eventos, Reservas y Entradas: quedan como están.
- La imagen `docs/diagramas-ms3/secuencia-compra.png`: se regenera a mano desde el `.mmd` si se quiere (no se genera en este cambio).
- Los diseños de cambios archivados, que conservan las rutas viejas como registro histórico.
- El texto de contexto de `openspec/config.yaml`.

## Servicios externos

- **Pagos (MS4)**: único servicio del que depende el cambio. Hay que coordinar el despliegue: el Pagos con rutas internas tiene que estar disponible (o configurar las rutas viejas mientras tanto).

## Impact

- Código: `ticketya.cliente.rest.PagosClienteRest` (rutas leídas de propiedades) y comentarios en `Compra`, `Reintegro`, `ReintegroService`, `DevolucionService` y `CancelacionService`.
- Configuración: `application.properties` suma `servicios.pagos.rutas.*`. El README documenta las propiedades nuevas.
- Pruebas: `PagosClienteRestTest` verifica cada ruta, el encabezado `Idempotency-Key` y las rutas configuradas. Comentario de `PagosFalso`.
- Documentación: `openapi.yaml`, `docs/historias-ms3.md`, `docs/diagramas-ms3.md`, `docs/diagramas-ms3/secuencia-compra.mmd`.
- Sin cambios en el modelo de datos ni en la API pública del servicio.
