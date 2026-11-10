## Why

Cuando se cancela un evento, hoy solo se reintegran las compras confirmadas. Una compra pendiente cuyo pago ya se aprobó (por ejemplo, porque falló la emisión de entradas, o porque el cobro se hizo pero se perdió la respuesta) queda con el dinero cobrado y sin reintegro automático. Es el punto abierto 5 de `docs/historias-ms3.md`. Además sigue abierto cuánto tiempo se recuerda la clave de idempotencia del pago (punto abierto 1), que se decide en este cambio.

## What Changes

- Al procesar la cancelación de un evento, el sistema también cierra sus **compras pendientes**:
  - **Con `pagoId`:** pasa la compra de pendiente a fallida con la actualización condicional y, solo si ese cambio se aplica, registra un reintegro por el total (origen `CANCELACION_EVENTO`, `reason` "Evento cancelado", `refundRequestId` propio guardado antes de llamar a Pagos) y lo pide.
  - **Sin `pagoId`:** antes de cerrarla consulta `GET /api/v1/payments/purchase/{purchaseId}`. Si hay un pago `APROBADO`, guarda ese `paymentId` y sigue como en el caso anterior. Si no hay ninguno aprobado, la pasa a fallida sin reintegro.
  - Si Pagos no responde a esa consulta, la compra queda pendiente y la cancelación queda en proceso, para retomarla con un aviso repetido.
- **Concurrencia con un pago retomado:** gana quien cambia primero el estado de la compra. Un pago retomado nunca confirma una compra fallida, y una compra que llegó a confirmarse sigue el camino normal de la cancelación.
- **Idempotencia:** un aviso repetido o un procesamiento retomado no duplica cierres ni reintegros; un reintegro de una compra cerrada que quedó pendiente se vuelve a pedir con el mismo `refundRequestId`.
- Los reintegros fallidos se reintentan con el mecanismo existente (`POST /reintegros/{id}/reintento` y el `/retry` de Pagos).
- **Límite aceptado en la Entrega 1:** si la emisión se hizo en Entradas pero se perdió la respuesta, esas entradas quedan en reintegro (por la marcación del evento) y nunca pasan a devueltas, porque este servicio no conoce su `idEntrada`.
- **Decisión: la clave de idempotencia del pago no vence en la Entrega 1**; se conserva mientras exista la compra. No se agrega una tarea de limpieza.
- `openapi.yaml`: el aviso de cancelación (`POST /eventos-cancelados`) describe el cierre de las compras pendientes; el pago de una compra cerrada por la cancelación responde con la compra fallida.
- `docs/historias-ms3.md`: E-1 incluye las compras pendientes y los puntos abiertos 1 y 5 pasan a "Resueltos" (al implementar).

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `cancelacion-de-eventos`: la cancelación cierra las compras pendientes del evento y reintegra el total de las que tienen un pago aprobado; deja de decir que las pendientes no generan reintegro.
- `pago-de-compra`: un pago que se retoma sobre una compra cerrada por la cancelación no cobra, no confirma la venta ni emite; y gana quien cambia primero el estado de la compra.

## Impact

- **Servicios externos:** Pagos (consulta de pagos por compra y reintegro, ya acordados) y Entradas (marcación por evento, sin cambios). No cambia nada con Eventos ni Reservas.
- **Código:** `CancelacionService` (nuevo procesamiento de compras pendientes y retoma de sus reintegros), `PagoService` (verificar el resultado del cambio a confirmada), `CompraRepository` (consulta de compras del evento por estado, si hace falta), pruebas de cancelación y de pago concurrente.
- **Base de datos:** sin cambios de esquema. Se reutilizan `Reintegro` y `EntradaDeReintegro`.
- **Documentación:** `compras/src/main/resources/static/openapi.yaml`, `docs/historias-ms3.md`.

## Fuera de alcance

- Liberar en Reservas los lugares de una compra pendiente que se cierra por la cancelación: el evento ya no existe y Reservas no lo pidió.
- Pasar a devueltas las entradas emitidas cuya respuesta se perdió (límite aceptado).
- Tarea programada de limpieza de claves de idempotencia (posible en la Entrega 2).
- Compras pendientes cobradas de eventos que **no** se cancelan: siguen como hoy, a la espera de que el cliente repita el pago.
- Cambios en los diseños archivados.
