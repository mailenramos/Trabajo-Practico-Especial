## Contexto

- `EntradasClienteRest` extiende `ClienteRest`, que arma un `RestClient` sin encabezados por defecto. El código de un 409 se lee de `error` (o `codigo`) con `codigoDeError`.
- `EntradasCliente.marcarEntradasDelEvento(idEvento)` devuelve solo `entradasMarcadas`. La ruta figura como provisoria.
- `emitir` traduce cualquier 409 a `ServicioExternoException`, así que `PagoService` lo reintenta como falla técnica.
- `DevolucionService.coordinar` ya captura `ServicioExternoException` y guarda el error de la solicitud. `EstadoEntradaConflictoException` conserva el código del 409.

## Decisiones

- **`UTILIZADA`:** se agrega al enum. La validación de nueva solicitud ya rechaza toda entrada que no sea VIGENTE con 409 `ENTRADA_NO_VIGENTE`; el mensaje pasará a nombrar el estado.
- **Aprobación con entrada utilizada:** Entradas responde 409 `ENTRADA_UTILIZADA` al pasar a reintegro. El cliente lo convierte en `EstadoEntradaConflictoException` (ya lo hace) y `coordinar` guarda el error en la solicitud, que queda APROBADA. No se llama a Pagos, así que no se mueve dinero. Se cubre con un test.
- **Encabezados:** `X-Service-Token` se toma de `SERVICIO_TOKEN`; si no está configurado no se envía y Entradas responde 403, que se trata como falla técnica reintentable. `X-Correlation-Id` se toma del pedido entrante (el del Gateway) mediante un filtro que lo guarda en el MDC; si no hay (tareas programadas, reintentos), se genera un UUID. Se aplican con un interceptor del `RestClient`, así valen para todas las llamadas del cliente de Entradas. El mismo identificador se reutiliza como `correlacion_id` de los avisos cuando exista.
- **Cierre del evento:** `marcarEntradasDelEvento` devuelve `ResultadoCierre(int entradasMarcadas, boolean cerrado)`. Si `cerrado` falta o es false, el paso de Entradas se considera incompleto: se guarda el error y la tarea de reanudación lo reintenta. La operación es idempotente en Entradas, así que repetirla es seguro. Se mantiene la ruta `POST /internal/eventos/{idEvento}/entradas/reintegro`.
- **`EVENTO_CERRADO`:** `emitir` lanza `EventoCerradoException` (no es `ServicioExternoException`, no se reintenta). `PagoService` trata la compra como la de un evento cancelado: no se emiten entradas y se registra el reintegro automático por el total pagado, con el mismo mecanismo y origen que usan las cancelaciones. El comprador recibe el aviso `EVENTO_CANCELADO` con `acontecimientoId = evento-cancelado-{eventoId}`, el mismo patrón que la cancelación, de modo que no se duplica. El estado final de la compra y el origen exacto del reintegro se definen al implementar, reutilizando lo que `CancelacionService` ya hace con compras de eventos cancelados.
- **Consulta por compra:** `EntradasCliente.listarPorCompra(idCompra)` llama a `GET /internal/compras/{idCompra}/entradas` y devuelve la lista (vacía si no se emitió, nunca 404). Se usa antes de reintentar una emisión incierta: si hay entradas se asignan sin volver a emitir; si no, se emite como hasta ahora.

## Decisión tomada al implementar: estado final de una compra con `EVENTO_CERRADO`

Se reutiliza lo que `CancelacionService` hace con una compra pendiente cobrada de un evento cancelado. No hace falta un estado nuevo:

- **Estado de la compra:** `FALLIDA`, sin entradas. El cambio es condicional (`PENDIENTE` → `FALLIDA`). Solo el pedido que lo aplica registra el reintegro y el aviso, así una repetición no los duplica.
- **Reintegro:** uno solo, por el total, con origen `CANCELACION_EVENTO` y motivo `Evento cancelado`. Es el mismo origen que usa la cancelación, así que si la cancelación del evento sigue en proceso y lo encuentra pendiente, lo vuelve a pedir con la misma clave.
- **Aviso:** `EVENTO_CANCELADO` con `acontecimientoId = evento-cancelado-{eventoId}` para el comprador. Si ya lo había recibido por otra compra del evento, la clave única del outbox evita el duplicado.
- **Transacción:** el cambio de estado, el registro del reintegro y el aviso se guardan juntos, en la transacción del pago. Después se pide el reintegro a Pagos, igual que en el caso de venta rechazada. Si Pagos no responde, el reintegro queda pendiente con el error.

## Riesgos

- Si Entradas todavía no devuelve `cerrado`, las cancelaciones quedarían reintentando. Mitigación: confirmar con Entradas que el campo está antes de integrar.
- El estado de una compra cobrada sin entradas puede requerir un estado nuevo; se decide al leer `PagoService` y se anota en `design.md`.
