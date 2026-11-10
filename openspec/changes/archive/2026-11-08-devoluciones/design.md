## Contexto

Ver `proposal.md` para el porqué. Estado actual del código en `compras/` que condiciona el
diseño:

- `Compra` guarda `pagoId`, `clienteId`, `eventoId` y su detalle; cada `DetalleCompra` tiene
  el `entradaId` que devolvió Entradas y el `precioUnitario` histórico. Una entrada se
  ubica en este servicio buscando su fila de detalle.
- La tabla `reintegros` (entidad `Reintegro`) ya existe: `refundRequestId` único,
  `pagosReintegroId`, estado `PENDIENTE`/`EXITOSO`/`FALLIDO`, `intentos`, `monto`,
  `motivo`, `origen` (`DEVOLUCION`, `CANCELACION_EVENTO`, `VENTA_RECHAZADA`) y fechas.
  No tiene lógica todavía.
- `PagosCliente` ya tiene `reintegrar`, `reintentarReintegro` y `consultarReintegro` con
  los nombres del contrato. `EntradasCliente` solo emite y `ReservasCliente` solo libera
  por pago rechazado.
- El pago (`PagoService`) corre sus pasos con la fila de la compra bloqueada y, ante una
  venta rechazada, hoy responde 409 `VENTA_RECHAZADA` y deja la compra pendiente.
- `X-User-Role` se lee pero ningún endpoint lo usa todavía. El grupo no fijó el valor del
  rol de administrador.

## Objetivos / No objetivos

**Objetivos:** que cada paso de una devolución aprobada se pueda repetir sin duplicar
efectos, que nada se revierta y que un reintegro nunca se pida dos veces por la misma
solicitud.

**No objetivos:** reintentos automáticos en segundo plano, notificaciones al cliente,
devolución de varias entradas en un solo pedido (cada entrada es una solicitud).

## Decisiones

### Modelo: solicitud de devolución
Tabla nueva `solicitudes_devolucion`:
- `id` (UUID): es el `devolucionId`. Lo genera este servicio.
- `clienteId`, `entradaId`, `compraId`, y una copia de lo que se muestra y se usa después:
  `sectorId`, `sectorNombre`, `fila`, `numero`, `monto` (precio unitario de la entrada) y
  datos del evento (`eventoId`, nombre, fecha de inicio).
- `estado`: `PENDIENTE`, `APROBADA`, `RECHAZADA`. `motivoRechazo`, `creadaEn`,
  `resueltaEn`, `resueltaPor`.
- Pasos de la coordinación: `entradaEnReintegro`, `lugarLiberado`, `entradaDevuelta`
  (booleanos) y `ultimoError` (texto del último paso que falló).
- `entradaActiva`: copia del `entradaId` mientras la solicitud está pendiente o aprobada,
  y `NULL` cuando se rechaza. Tiene restricción única. MySQL admite varios `NULL` en un
  índice único, así que una entrada puede tener muchas solicitudes rechazadas pero una
  sola activa.

**Alternativa descartada:** validar "una sola activa" solo con una consulta previa. Dos
pedidos simultáneos pasarían los dos; la restricción única lo impide en la base.

El reintegro de la devolución es una fila de `reintegros` con
`refundRequestId = devolucionId` y origen `DEVOLUCION`. La solicitud y el reintegro tienen
estados independientes (ver specs `devolucion-de-entradas` y `reintegros`).

### Solicitar (D-1)
Orden de validación, de lo más barato a lo más caro:
1. Buscar la fila de detalle por `entradaId`. Si no existe, 404 `ENTRADA_NO_ENCONTRADA`.
2. La compra debe ser del usuario (si no, 403 `ENTRADA_AJENA`) y estar confirmada con
   `pagoId`.
3. Si ya hay una solicitud activa para la entrada: si es del usuario y está pendiente se
   devuelve (200); si está aprobada, 409 `DEVOLUCION_EN_CURSO`.
4. Entradas: `GET /internal/entradas/{id}`. `idCliente` debe ser el usuario (403) y el
   estado vigente (si no, 409 `ENTRADA_NO_VIGENTE`).
5. Eventos: `GET /internal/eventos/{eventoId}`. Si está cancelado, 409
   `EVENTO_CANCELADO`. Si `fechaInicio - ahora` es de 72 horas o menos, 409
   `PLAZO_VENCIDO`. Se usa la fecha de Eventos y no la guardada en la compra, porque el
   evento puede haberse reprogramado.
6. Se inserta la solicitud. Si la restricción única de `entradaActiva` salta (pedido
   simultáneo), se lee la ganadora y se aplica el punto 3.

Si Entradas o Eventos no responden: 503 y no se registra nada.

### Rol de administrador
Propiedad `compras.rol-administrador`, por defecto `ADMIN`, comparada sin distinguir
mayúsculas con `X-User-Role`. Aprobar, rechazar, reintentar y listar reintegros exigen ese
rol (403 `SOLO_ADMINISTRADOR`). Un administrador también puede consultar cualquier
solicitud.

### Aprobar (D-2): atomicidad y orden
1. En una transacción, con la fila de la solicitud bloqueada (`SELECT ... FOR UPDATE`):
   cambio condicional `PENDIENTE → APROBADA`. Si no está pendiente, 409
   `DEVOLUCION_YA_RESUELTA`. En la misma transacción se crea el reintegro `PENDIENTE`. Así
   una solicitud aprobada siempre tiene su reintegro y dos aprobaciones simultáneas no
   crean dos.
2. Se coordina con la fila todavía bloqueada (mismo patrón que el pago: dos coordinaciones
   de la misma devolución no avanzan a la vez). Cada paso marca su booleano al terminar:
   1. entrada a en reintegro (Entradas);
   2. liberar el lugar (Reservas, motivo `DEVOLUCION_APROBADA`, con `devolucionId` y solo
      ese lugar);
   3. reintegro (Pagos, ver "Reintegros");
   4. con el reintegro exitoso, entrada a devuelta (Entradas).
3. Si un paso falla por una falla técnica, se guarda `ultimoError`, se confirma la
   transacción con lo que se hizo y se responde. Los pasos siguientes no se ejecutan.

Respuesta: 200 si la coordinación quedó completa (los cuatro pasos hechos); 202 si algo
quedó pendiente (un paso falló o el reintegro no es exitoso), con la solicitud y su
`pasoPendiente`.

### Falla de una llamada distinta del reintegro: registrar y retomar
**Decisión:** la devolución queda aprobada con la coordinación incompleta y un
administrador la retoma con `POST /reintegros/{reintegroId}/reintento`; se sigue desde el
primer paso sin marcar.

**Alternativas descartadas:**
- *Revertir:* no hay operaciones inversas. Reservas no vuelve a retener un lugar liberado
  (y otro cliente pudo haberlo reservado) y Entradas no pasa una entrada en reintegro a
  vigente. Además el administrador ya decidió devolver.
- *Reintentar automáticamente en el momento:* si el otro servicio está caído, reintentar
  en el mismo pedido solo alarga la respuesta. Sin tarea periódica (Entrega 1), el
  reintento lo dispara el administrador.

**Por qué es seguro repetir cada paso:**
- Entradas: antes de pedir un cambio de estado se consulta la entrada (`GET`). Si ya está
  en reintegro (o devuelta) el paso se da por hecho. Esto evita depender de los códigos de
  respuesta de los cambios de estado, que Entradas no confirmó.
- Reservas: la liberación por devolución es repetible por `devolucionId` (204).
- Pagos: el reintegro es idempotente por clave (ver abajo).

Si el proceso se corta después de un efecto y antes de guardar su marca, al retomar se
repite ese paso, que es repetible.

### Reintegros
Un servicio de reintegros único, que usan la devolución, la venta rechazada y después la
cancelación de eventos:
- **Pedir:** `POST /api/v1/payments/{pagoId}/refunds` con
  `Idempotency-Key = refundRequestId`, `amount = monto` y `reason = motivo`. El motivo es
  obligatorio en la entidad (no vacío): "Devolución de entrada <entradaId>" o "Reserva
  vencida antes de confirmar la venta". Con la respuesta se guarda `pagosReintegroId`, el
  estado (`EXITOSO`/`FALLIDO`) y `intentos = attemptCount`.
- **Sin respuesta** (corte o caída): el reintegro queda `PENDIENTE` sin
  `pagosReintegroId`. Se retoma repitiendo el mismo `POST` con la misma clave: Pagos
  devuelve el original (200) y no duplica.
- **409 o 400 al pedir** (no debería pasar: pago no aprobado, suma mayor que lo cobrado,
  motivo vacío): queda `PENDIENTE` con `ultimoError` para resolverlo a mano. Columna nueva
  `ultimoError` en `reintegros`.
- **Reintentar un fallido:** `POST /api/v1/refunds/{pagosReintegroId}/retry`, sin cuerpo
  ni clave. Si responde 409, `GET /api/v1/refunds/{pagosReintegroId}` y se guarda el
  estado que informa. Nunca se crea un `refundRequestId` nuevo.
- **Reintentar un exitoso:** no se llama a Pagos.
- Cada reintento corre con la fila del reintegro bloqueada.

Para una devolución, `POST /reintegros/{id}/reintento` retoma la devolución completa
(pasos 1 a 4), no solo el reintegro.

### Pago aprobado y venta rechazada
**Decisión:** reintegro automático por el total y compra fallida (la opción recomendada).
Dejarla pendiente retiene el dinero del cliente sin entradas y sin forma de terminar.

En `PagoService`, al recibir `ConfirmacionRechazadaException`, dentro de la transacción
del pago: cambio condicional `PENDIENTE → FALLIDA` y, solo si cambió, se crea el
reintegro (`origen = VENTA_RECHAZADA`, `refundRequestId` UUID nuevo, monto = total) y se
pide. Atarlo al cambio condicional garantiza un único reintegro por compra. No se libera
la reserva: ya está vencida o liberada.

La respuesta pasa a 200 con la compra fallida y su reintegro, y la clave de idempotencia
queda completada. Un pago repetido ve la compra resuelta y devuelve el estado actual sin
cobrar ni pedir otro reintegro. Las compras que quedaron pendientes con este caso antes del
cambio se resuelven al repetir el pago con una clave nueva: la confirmación vuelve a
rechazarse y se sigue el camino nuevo.

### Compra con sus reintegros
`CompraRespuesta` suma `reintegros: [{id, monto, origen, estado}]`, leídos por `compraId`.

### Endpoints de este cambio
| Método y ruta | Quién | Respuestas |
| --- | --- | --- |
| `POST /devoluciones` body `{"entradaId"}` | Cliente | 201 nueva; 200 si ya tenía una pendiente; 400; 403 `ENTRADA_AJENA`; 404 `ENTRADA_NO_ENCONTRADA`; 409 `ENTRADA_NO_VIGENTE`, `PLAZO_VENCIDO`, `EVENTO_CANCELADO`, `DEVOLUCION_EN_CURSO`; 503 |
| `GET /devoluciones` (`?estado=`) | Cliente: las suyas. Administrador: todas | 200 |
| `GET /devoluciones/{devolucionId}` | Titular o administrador | 200; 403 `ACCESO_DENEGADO`; 404 `DEVOLUCION_NO_ENCONTRADA` |
| `POST /devoluciones/{devolucionId}/aprobacion` | Administrador | 200 completa; 202 coordinación pendiente; 403 `SOLO_ADMINISTRADOR`; 404; 409 `DEVOLUCION_YA_RESUELTA` |
| `POST /devoluciones/{devolucionId}/rechazo` body `{"motivo"}` | Administrador | 200; 400 `VALIDACION`; 403; 404; 409 `DEVOLUCION_YA_RESUELTA` |
| `GET /reintegros` (`?estado=`) | Administrador | 200; 403 |
| `POST /reintegros/{reintegroId}/reintento` | Administrador | 200 si quedó exitoso (y, en una devolución, completa); 202 si sigue pendiente o fallido; 403; 404 `REINTEGRO_NO_ENCONTRADO` |

La respuesta de una devolución incluye su estado, motivo de rechazo, lugar, evento, monto,
`coordinacion: {completa, pasoPendiente, ultimoError}` y `reintegro: {id, estado,
intentos}`.

## Contratos con otros servicios

### Acordados
- **Eventos:** `GET /internal/eventos/{eventoId}` (`fechaInicio`, `estado`). 404 si no
  existe.
- **Reservas:** `POST /disponibilidad/reservas/{reservaId}/liberacion` con
  `{"motivo":"DEVOLUCION_APROBADA","devolucionId":"...","detalles":[...]}`. Puede ser
  parcial; el mismo `devolucionId` con otros detalles da 409. 204 repetible, 400, 404,
  409.
- **Pagos:** `POST /api/v1/payments/{paymentId}/refunds` (`Idempotency-Key` y `reason`
  obligatorios; 400 si falta `reason`), `POST /api/v1/refunds/{refundId}/retry` (409 si no
  está fallido) y `GET /api/v1/refunds/{id}`.

### Propuesta de Entradas (rutas propuestas por Entradas, no inventadas)
- `GET /internal/entradas/{id}`: `idCliente`, `estado`, `evento.comienzaEn`.
- `POST /internal/entradas/{id}/reintegro`: pasa la entrada a en reintegro e invalida el QR.
- `POST /internal/entradas/{id}/devolucion`: pasa la entrada a devuelta.

### A acordar
- **Entradas:** formato de error (propuso uno distinto al del grupo); códigos de respuesta
  de `/reintegro` y `/devolucion`, incluido qué responde si la entrada ya está en ese
  estado; cómo representa los sectores no numerados; campos exactos del pedido de
  emisión. Mientras tanto el cliente trata cualquier 2xx como éxito, consulta el estado con
  `GET` antes de cada cambio y toma cualquier otra respuesta como falla del paso.
- **Reservas:** forma de un lugar de sector no numerado en `detalles` de la liberación.
  Se asume la misma que en `GET /disponibilidad/reservas/{id}`: `{"sectorId","cantidad":1}`;
  en un sector numerado, `{"sectorId","fila","numero"}`.
- **Grupo:** valor de `X-User-Role` para el administrador (se asume `ADMIN`,
  configurable).

## Riesgos / Trade-offs

- [La fila de la solicitud queda bloqueada mientras se llama a tres servicios] → timeouts
  de 2 s de conexión y 5 s de lectura ya configurados; el bloqueo solo frena otra
  coordinación de la misma devolución.
- [Entradas cambia los códigos o el formato que se asumen] → el cliente decide por `GET`
  del estado, no por códigos, y está aislado detrás de `EntradasCliente`.
- [Un reintegro rechazado por Pagos con 409/400 al pedirlo queda pendiente] → queda con
  `ultimoError` y aparece en `GET /reintegros?estado=PENDIENTE` para resolverlo a mano.
- [Una devolución incompleta depende de que un administrador la retome] → se lista en
  `GET /devoluciones?estado=APROBADA` con `coordinacion.completa = false`; una tarea
  periódica queda para después de la Entrega 1.
- [El plazo de 72 h depende de la fecha de Eventos] → se compara en UTC (`Instant`).

## Plan de migración

- `ddl-auto=update` crea `solicitudes_devolucion` y agrega `ultimo_error` a `reintegros`.
  `motivo` pasa a obligatorio en la entidad; la tabla no tiene filas en producción.
- Rollback: volver a la versión anterior deja las tablas nuevas sin usar; no hay datos que
  transformar.
