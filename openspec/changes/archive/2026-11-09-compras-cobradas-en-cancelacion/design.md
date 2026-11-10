## Context

Ver proposal.md (Why). Estado actual observado en el código:

- `CancelacionService.procesar(eventoId)` marca las entradas en Entradas y recorre solo `findByEventoIdAndEstadoOrderByFechaAsc(eventoId, CONFIRMADA)`. Por compra: `prepararReintegro` (en transacción, con `compras.bloquear` = `PESSIMISTIC_WRITE`) registra un `Reintegro` con origen `CANCELACION_EVENTO` y sus `EntradaDeReintegro`; después `completar(reintegroId, false)` lo pide a Pagos y pasa las entradas a devuelta. Si una compra falla, la cancelación queda `EN_PROCESO` y un aviso repetido vencido la retoma.
- `PagoService.pasos` corre en **una** transacción con la compra bloqueada (`compras.bloquear`) mientras cobra, confirma la venta y emite; si la compra no está `PENDIENTE`, devuelve la compra sin hacer nada. El paso final usa `compras.cambiarEstado(id, PENDIENTE, CONFIRMADA)` (UPDATE condicional) pero **ignora** su resultado.
- `PagoService.ventaRechazada` ya resuelve un caso parecido: `cambiarEstado(PENDIENTE → FALLIDA)` y solo si devuelve 1 registra un reintegro por el total y lo pide.
- `ReintegroService.pagoDeLaCompra` toma el `pagoId` de la compra: una compra cerrada sin `pagoId` guardado no podría reintentarse.
- `PagosCliente.listarPagosPorCompra(purchaseId)` ya existe (`GET /api/v1/payments/purchase/{purchaseId}`; 200 con lista, vacía si no hay pagos).
- La emisión asigna `entradaId` a cada detalle; si la respuesta se pierde, los detalles quedan sin `entradaId`.

## Goals / Non-Goals

**Goals:**
- Cerrar las compras pendientes de un evento cancelado y reintegrar el total cuando hubo cobro, sin duplicar nada ante avisos repetidos ni carreras con un pago retomado.
- Reutilizar el registro y reintento de reintegros existente.
- Dejar escrita la decisión sobre la retención de la clave de idempotencia.

**Non-Goals:**
- Nuevos endpoints, nuevos estados o cambios de esquema.
- Liberar lugares en Reservas para las compras cerradas.

## Contratos usados (ya acordados, sin cambios)

| Servicio | Operación | Uso en este cambio |
| --- | --- | --- |
| Pagos | `GET /api/v1/payments/purchase/{purchaseId}` | Compra pendiente sin `pagoId`: buscar un pago `APROBADO` (200 con lista; vacía si no hay). |
| Pagos | `POST /api/v1/payments/{paymentId}/refunds` con `Idempotency-Key` | Reintegro por el total: `refundRequestId` propio (también la clave), `amount` = total, `reason` "Evento cancelado". |
| Pagos | `POST /api/v1/refunds/{refundId}/retry` | Reintento de un reintegro fallido, vía `POST /reintegros/{id}/reintento`. |
| Entradas | `POST /internal/eventos/{idEvento}/entradas/reintegro` y `POST /internal/entradas/{id}/devolucion` | Sin cambios; la devolución solo para entradas con `entradaId` conocido. |

**Contrato a acordar:** ninguno nuevo. La marcación por evento sigue como contrato a acordar con Entradas (cambio `contrato-entradas`).

## Decisions

### 1. Orden del procesamiento: primero pendientes, después confirmadas

`procesar` recorre primero las compras `PENDIENTE` del evento y después vuelve a consultar las `CONFIRMADA`. Si un pago retomado confirma una compra mientras la cancelación espera su bloqueo, la cancelación la encuentra confirmada (decisión 3) y la procesa por el camino normal en ese mismo momento; la segunda consulta de confirmadas cubre además las que se confirmaron antes de ser tocadas. Una compra procesada dos veces por el camino de confirmadas no duplica nada: `prepararReintegro` reutiliza el reintegro existente.

Alternativa descartada: una sola consulta de "pendientes y confirmadas" al principio; una compra confirmada después de la consulta se perdería.

### 2. Cierre de una compra pendiente (`cerrarPendiente`), en una transacción con la compra bloqueada

```
bloquear compra (PESSIMISTIC_WRITE)
si estado == CONFIRMADA        → salir de la transacción y seguir por procesarCompra (camino normal)
si estado != PENDIENTE         → nada (ya cerrada o fallida): retomar su reintegro si existe (decisión 5)
si pagoId == null:
    pagos = listarPagosPorCompra(id)        ← Pagos caído: ServicioExternoException → falla de la compra,
                                              la compra sigue pendiente y la cancelación queda EN_PROCESO
    si hay APROBADO → compra.setPagoId(paymentId); guardar
si cambiarEstado(id, PENDIENTE, FALLIDA) == 1 y pagoId != null:
    registrar Reintegro(total, "Evento cancelado", CANCELACION_EVENTO, refundRequestId = UUID nuevo)
    registrar EntradaDeReintegro por cada detalle con entradaId != null
commit → completar(reintegroId, false)  (pide a Pagos fuera de la transacción, como hoy)
```

- La actualización condicional es la que decide: solo quien la aplica registra el reintegro, así nunca hay dos (igual que `ventaRechazada`).
- El reintegro y el `pagoId` se guardan en la misma transacción que el cambio a fallida, **antes** de llamar a Pagos: si el servicio se cae después, el procesamiento retomado encuentra el reintegro pendiente y lo pide con el mismo `refundRequestId`.
- Monto: `compra.getTotal()` (lo cobrado), no la suma de entradas vigentes: en una compra pendiente no hubo devoluciones aprobadas.
- Las entradas con `entradaId` conocido se asocian al reintegro para pasarlas a devuelta con el reintegro exitoso (reutiliza `completar`). Las emitidas cuya respuesta se perdió no se conocen: quedan en reintegro (límite aceptado).
- Consultar a Pagos con la compra bloqueada es lo mismo que ya hace `PagoService.pasos`; evita que un pago retomado cobre entre la consulta y el cierre.

Alternativa descartada: cerrar sin bloquear, solo con el UPDATE condicional. Funciona para el cambio de estado, pero la consulta a Pagos quedaría fuera de la exclusión mutua con un pago en curso.

### 3. Concurrencia con un pago retomado

Las dos operaciones bloquean la misma fila (`compras.bloquear`), así que se serializan:
- **Gana el pago:** termina con la compra `CONFIRMADA`; la cancelación obtiene el bloqueo, la ve confirmada y la procesa por el camino normal (decisión 1).
- **Gana la cancelación:** deja la compra `FALLIDA`; el pago obtiene el bloqueo, ve que no está pendiente y responde la compra sin cobrar, confirmar ni emitir (comportamiento actual de `pasos`).
- **Defensa adicional:** el paso final de `pasos` pasa a verificar el resultado de `cambiarEstado(PENDIENTE → CONFIRMADA)`; si es 0 no confirma y responde con el estado real. Con el bloqueo no debería ocurrir, pero garantiza que un pago nunca confirma una compra fallida aunque cambie el manejo de bloqueos.

### 4. Pago sobre una compra cerrada

No se agrega lógica: `pasos` ya devuelve la compra (200) cuando no está pendiente, y `tomarClave` ya corta antes en una clave `COMPLETADA`. Una clave nueva sobre una compra cerrada responde la compra `FALLIDA` con su reintegro, sin llamar a Pagos.

### 5. Idempotencia y retoma

- Aviso repetido: sin cambios en `recibir` (200 si procesada; 202 y retoma si vencida).
- Procesamiento retomado: además de pendientes y confirmadas, `procesar` recorre las compras `FALLIDA` del evento que tengan un reintegro con origen `CANCELACION_EVENTO` en estado `PENDIENTE` y llama a `completar(reintegroId, false)`. Esa pasada va **antes** que la de pendientes, así solo retoma cierres de un procesamiento anterior y no vuelve a pedir en la misma corrida un reintegro que acaba de quedar sin respuesta. El orden queda: fallidas con reintegro pendiente, pendientes, confirmadas. Las fallidas sin ese reintegro (rechazadas antes, o cerradas sin cobro) no se tocan. Los reintegros `FALLIDO` siguen esperando a un administrador (`POST /reintegros/{id}/reintento`), como hoy.
- Una compra ya cerrada no se vuelve a cerrar porque deja de estar `PENDIENTE`.

### 6. Retención de la clave de idempotencia del pago (punto abierto 1)

**Decisión:** en la Entrega 1 la clave no vence; se conserva mientras exista la compra (las compras no se borran). No se agrega una tarea de limpieza.

**Por qué es seguro igual:** aunque una clave se olvidara, repetir el pago no cobra dos veces:
- si la compra ya tiene `pagoId`, `pasos` no cobra;
- si otra clave ya intentó pagar la compra, antes de cobrar se consulta a Pagos el pago aprobado de la compra;
- y si igual se pide un cobro con una clave nueva sobre una compra ya pagada, Pagos responde 409 y `cobrar` usa el pago aprobado que informa.

Una limpieza agregaría código y pruebas sin sumar seguridad. **Entrega 2:** puede sumarse una limpieza programada de las claves `COMPLETADA` (por ejemplo, 24 horas después de completarse).

## Risks / Trade-offs

- [Entradas emitidas cuya respuesta se perdió quedan en reintegro para siempre] → límite aceptado en la Entrega 1; queda documentado en `historias-ms3.md` y en `openapi.yaml`.
- [Pagos no responde la consulta de pagos de una compra sin `pagoId`] → la compra queda pendiente y la cancelación `EN_PROCESO`; un aviso repetido pasado el plazo la retoma. No se cierra a ciegas porque podría haber un cobro.
- [Un cobro que Pagos registre después de la consulta (pedido en vuelo de un pago cuyo proceso murió sin soltar el bloqueo)] → con el bloqueo pesimista y la transacción del pago, un pago vivo bloquea la compra; si su proceso murió, la transacción se revirtió y su pedido a Pagos ya terminó o falló. Riesgo residual bajo; un cobro posterior a la cancelación quedaría como pago de una compra fallida, visible en Pagos.
- [Bloquear la compra mientras se consulta a Pagos alarga la transacción] → igual que en `PagoService`; los timeouts de cliente (2 s / 5 s) acotan la espera.
- [Los lugares de una compra cerrada no se liberan en Reservas] → el evento está cancelado; fuera de alcance.

## Migration Plan

Sin migración de base. El despliegue cambia el comportamiento de los avisos nuevos; las cancelaciones ya `PROCESADA` no se reprocesan (sus compras pendientes cobradas, si las hubiera, se resuelven a mano). Rollback: volver a la versión anterior; los reintegros registrados quedan válidos y se pueden reintentar.
