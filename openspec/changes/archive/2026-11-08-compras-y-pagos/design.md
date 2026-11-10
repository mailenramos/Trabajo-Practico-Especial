## Contexto

Microservicio 3 de TicketYa (Java 21, Spring Boot, Spring Data JPA, MySQL propia, puerto
8083). Se comunica con los servicios 1, 2, 4 y 5 por REST. El usuario llega identificado
por los encabezados X-User-Id y X-User-Role (sin autenticación en la Entrega 1). Las
operaciones entre servicios no pasan por el API Gateway.

Puertos conocidos: 8080 Reservas, 8081 Eventos, 8083 Compras, 8084 Pagos (propuesto por
Pagos, confirmado de nuestro lado), 8085 Entradas. El puerto del Gateway no está definido.

## Objetivos / No objetivos

**Objetivos:** compra desde una reserva, cobro coordinado, confirmación de venta,
emisión de entradas, liberación ante rechazo, reintento idempotente, consulta de compras.

**No objetivos:** devoluciones, reintegros por solicitud y cancelación de eventos (cambios
`devoluciones` y `cancelacion-de-eventos`). Este cambio solo deja preparado el modelo de
datos de los reintegros (tabla `reintegros`), que esos cambios completan con su lógica y
sus endpoints.

## Decisiones

### Flujo de compra y pago (orden)
1. Validar titularidad de la reserva y consultar precios.
2. Registrar la compra como pendiente.
3. Consultar el estado de la reserva (verificación preventiva).
4. Pedir el cobro al servicio de pagos.
5. Pago aprobado: guardar el `pagoId`, confirmar la venta y luego emitir las entradas. La
   compra pasa a confirmada solo cuando las entradas ya fueron emitidas.
6. Pago rechazado: liberar los lugares. La compra pasa a fallida.

Un rechazo de Pagos no es un error técnico: llega como respuesta normal (201) con estado
`RECHAZADO`. Se interpreta por el campo `status` y no solo por el código HTTP.

### Transiciones de estado atómicas
El cambio de estado se hace con una actualización condicional
(`UPDATE ... SET estado = ? WHERE id = ? AND estado = 'PENDIENTE'`). Si afecta cero
filas, otra ejecución ya resolvió la compra y no se repite el efecto. Dos pagos
simultáneos de la misma reserva no pueden prosperar los dos.

### Idempotencia del pago (lado de este servicio)
El cliente envía `Idempotency-Key` en `POST /compras/{id}/pago`. La clave se acota por
cliente: la identidad es el par (`clienteId`, `clave`), con clave primaria compuesta. Así,
dos clientes que envían la misma clave (por ejemplo "1") no se pisan.

Cada fila guarda un resumen de los datos del pedido (compra y medio de pago), el estado
(en proceso o completada) y el resultado. Misma clave y mismos datos: resultado original.
Misma clave con datos distintos: 409. Primer pago aún en proceso: respuesta "en curso"
(202). Ese "en curso" aparece solo cuando dos pedidos con la misma clave llegan al mismo
tiempo, porque Pagos siempre responde en el momento.

La clave tiene tres estados:
- `EN_PROCESO`: un pedido la está procesando.
- `COMPLETADA`: terminó con un resultado definitivo (compra confirmada o fallida, o un
  error que no cambia al repetir, como la reserva vencida). El reintento recibe ese
  resultado tal cual.
- `INTERRUMPIDA`: la operación quedó a medias, porque otro servicio no respondió (503)
  o porque falló la emisión de entradas (202). Un reintento con la misma clave la retoma
  desde el último paso hecho y conserva la misma `claveCobro`.

Retomar una clave usa una actualización condicional sobre su estado y el momento en que
se tomó (`tomadaEn`): si dos reintentos llegan juntos, solo uno la toma y el otro recibe
"en curso". Si el servicio se cae a mitad de un pago, la clave queda `EN_PROCESO`; pasado
un plazo configurable (`compras.idempotencia.plazo-en-proceso`, 60 s por defecto) se
considera abandonada y un reintento la retoma. Antes de ese plazo, el reintento recibe
"en curso".

La fila guarda además la `claveCobro`: una clave que genera este servicio (UUID) y que se
envía a Pagos como `Idempotency-Key` del cobro. No se usa la clave del cliente, porque en
Pagos la clave es global y dos clientes podrían chocar. Retomar un pago reutiliza siempre
la misma `claveCobro`.

### Idempotencia del cobro (lado de Pagos)
- `Idempotency-Key` hacia Pagos es obligatoria y distinta del `purchaseId`.
- Misma clave y mismos datos: Pagos devuelve el pago original (200), sin cobrar de nuevo.
- Misma clave con compra, monto o medio distintos: 409.
- Clave nueva sobre una compra que ya tiene un pago aprobado: 409.
- Tras un rechazo hace falta una clave nueva; con la misma se recibe el rechazo original.
  En este servicio un rechazo deja la compra fallida, así que el cliente reserva de nuevo
  y empieza una compra nueva.

Antes de cobrar, si la compra ya tiene `pagoId`, no se vuelve a cobrar: se retoma desde
el paso siguiente.

### Estado incierto del cobro (cortes de conexión)
Si el servicio no recibe la respuesta de Pagos (corte o caída), el cobro queda con estado
incierto. Al repetir el pago con la misma clave se consulta
`GET /api/v1/payments/purchase/{purchaseId}`, que devuelve 200 con la lista de pagos de
la compra (vacía `[]` si no hay ninguno). Solo cuenta el pago `APROBADO`:
- si hay un pago aprobado, se guarda su `pagoId` y se sigue con la confirmación;
- si la lista está vacía o no tiene un pago aprobado, se repite el `POST` con la misma
  `claveCobro`, que es seguro. Si el intento anterior había sido rechazado, Pagos
  devuelve ese rechazo original y la compra queda fallida.

El mismo criterio se usa si el cobro responde 409 (la compra ya tiene un pago aprobado
con otra clave): se toma el pago aprobado de la lista.

No se agrega una tarea periódica en la Entrega 1: la recuperación ocurre cuando el cliente
repite el pago (con la clave `INTERRUMPIDA`, o `EN_PROCESO` vencida si el servicio se
cayó). La consulta a Pagos también se hace cuando otra clave del cliente ya intentó pagar
la misma compra.

### Reintentos de la emisión de entradas
Si el pago se aprobó y la venta se confirmó pero la emisión falla, se reintenta hasta 3
veces. Si sigue fallando, la compra queda pendiente y el pago responde **202** con la
compra (pendiente y sin entradas), para indicar que la operación no terminó. La clave
queda `INTERRUMPIDA`. Si el cliente repite el pago con la misma clave, el servicio retoma
desde el último paso hecho: no vuelve a cobrar ni a confirmar la venta, y solo repite la
emisión. Es seguro porque Entradas es idempotente por `idCompra`.

Para saber qué pasos ya se hicieron, la compra guarda el `pagoId` (cobro aprobado) y la
marca `ventaConfirmada` (Reservas ya confirmó la venta). Aunque la confirmación de
Reservas es repetible, la marca evita volver a pedirla al retomar. Cada fila del detalle
guarda su `entradaId`: si todas lo tienen, no se vuelve a emitir.

### Una compra por reserva
La tabla de compras tiene una restricción única sobre `reservaId`. Hace falta porque la
confirmación de Reservas es repetible (204 también si ya estaba confirmada): con dos
compras de la misma reserva pagadas a la vez, las dos cobrarían y las dos confirmarían.

Si se repite `POST /compras` con una reserva que ya tiene compra:
- mismo cliente y compra pendiente: se devuelve esa compra (200), sin consultar de nuevo
  a Eventos ni recalcular el total. Así un reintento por un corte de conexión no falla;
- compra ya confirmada o fallida: 409 `COMPRA_YA_INICIADA`;
- reserva de otro cliente: 403 `RESERVA_AJENA`, como en cualquier compra.

Si dos pedidos de la misma reserva llegan al mismo tiempo, la restricción única deja
registrar uno solo; el otro recibe la compra registrada.

### Identificadores
- `compraId` y `devolucionId`: UUID, generados por este servicio. El `compraId` se envía
  tal cual a Pagos como `purchaseId`.
- Reserva y entrada: UUID, los define Reservas y Entradas.
- Pago y reintegro de Pagos: UUID, los define Pagos.
- Evento y sector: texto, porque Eventos usa MongoDB (no son UUID).
- Se guardan como texto, sin claves foráneas, porque esas tablas no están en esta base.

### Lugares y detalle de compra
Un lugar se identifica por (sectorId, fila, numero). La fila es texto (por ejemplo "A").
En sectores no numerados no hay fila ni número. El detalle guarda **una fila por
entrada** (sin cantidad): una reserva de 2 lugares en un sector no numerado genera 2
filas. Cada fila guarda además el `entradaId` que devuelve Entradas.

### Datos que se guardan al comprar
La compra guarda el `eventoId`, el nombre del evento y su fecha de inicio (necesaria
para emitir las entradas y para buscar las compras de un evento cancelado). También
guarda el `pagoId` que devuelve Pagos al aprobar el cobro, necesario para pedir
reintegros después, y la marca `ventaConfirmada`. Cada fila del detalle guarda el nombre del sector, el precio unitario
al momento de la compra y el `entradaId`. No se vuelven a consultar a Eventos para
mostrar compras ya hechas.

### Medio de pago
Solo se aceptan los cuatro medios de Pagos: `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER` y
`DIGITAL_WALLET`. Cualquier otro valor se rechaza con 400 antes de llamar a Pagos. Pagos no
maneja monedas: el monto va sin moneda, se asume en pesos, es positivo y tiene como
máximo 2 decimales. El total de la compra respeta esa escala.

### Reintegros (modelo de datos)
La tabla `reintegros` registra cada pedido de reintegro a Pagos:
- `refundRequestId` (UUID, único): identifica la solicitud. En una devolución es el
  `devolucionId`. En reintegros que no vienen de un cliente (cancelación de evento, venta
  rechazada tras un cobro aprobado) lo genera este servicio.
- La `Idempotency-Key` que se envía a Pagos es el mismo valor que el `refundRequestId`.
- `pagosReintegroId`: el ID que devuelve Pagos (vacío hasta recibir respuesta).
- Estado propio: `PENDIENTE` (entre registrar y recibir la respuesta), `EXITOSO` o
  `FALLIDO`. Pagos nunca devuelve pendiente: un reintegro termina siempre exitoso o
  fallido en la misma llamada.
- `intentos`, `monto`, `motivo`, `origen` y fechas.

Reglas del contrato de Pagos que usan los cambios siguientes:
- Solo se reintegra sobre pagos aprobados (si no, 409).
- Se aceptan varios reintegros parciales mientras la suma no supere lo cobrado. Los
  fallidos no descuentan saldo.
- Un `refundRequestId` ya usado con otra clave da 409.
- Un reintegro fallido se reintenta solo con `POST /api/v1/refunds/{refundId}/retry`, sin
  cuerpo ni clave nueva. Nunca se crea un `refundRequestId` nuevo para reintentar, porque
  duplicaría la devolución. Si `/retry` responde 409, se consulta el estado con
  `GET /api/v1/refunds/{id}`: puede que ya haya salido bien.

### Formato de errores
Todos los errores usan {"timestamp","status","error","mensaje","campos"?}, el formato
que acordó el grupo. Pagos usa el mismo formato, con el timestamp en ISO-8601.

### Endpoints de este cambio (propuesta enviada al grupo)
- `POST /compras` con `{"reservaId":"..."}`. Responde 201 con la compra pendiente, 200 si
  el mismo cliente repite el pedido y la compra sigue pendiente, 403 si la reserva es de
  otro cliente, 404 si no existe y 409 si no está vigente o su compra ya se resolvió.
- `POST /compras/{compraId}/pago` con encabezado `Idempotency-Key` y
  `{"medioDePago":"..."}`. Responde 200 (confirmada o fallida); 202 si el primer pago
  sigue en proceso o si la emisión de entradas falló y la compra quedó pendiente; 400 si
  el medio de pago no es válido o falta la clave; 409 si la reserva venció, la venta fue
  rechazada o la clave se usó con datos distintos; 503 si otro servicio no respondió
  (se retoma repitiendo con la misma clave).
- `GET /compras` y `GET /compras/{compraId}`. Solo devuelven compras del usuario.

### Pruebas
Los clientes de los otros servicios se acceden mediante interfaces, de modo que las
pruebas unitarias los reemplazan por dobles de prueba. Pagos todavía no está implementado
(su contrato está cerrado), así que el doble de prueba de Pagos es lo que se usa hasta
que publique su servicio y su colección de Postman. Se incluye una prueba de dos pagos
simultáneos de la misma reserva, donde solo uno prospera.

## Contratos con otros servicios

### Eventos (servicio 1) — confirmado
- `GET /internal/eventos/{eventoId}`: 200 con `eventoId`, `nombre`, `fechaInicio`
  (ISO-8601, UTC), `estado` y `sectores` (`sectorId`, `nombre`, `precio` decimal).
  404 si no existe. Devuelve el evento en cualquier estado. Llamada directa, sin
  Gateway ni encabezados de usuario.
- `POST /eventos-cancelados` (Eventos llama a este servicio): body `eventoId`, `motivo`,
  `canceladoEn`. 202 si lo acepta, 200 si ya lo procesó. Idempotente por `eventoId`.
  Si este servicio no responde, la cancelación queda hecha en Eventos y el aviso se
  reenvía.

### Reservas (servicio 2) — confirmado
El cliente crea la reserva directo en Reservas, por el Gateway, con un `solicitudId`
para que el reintento no la duplique. Este servicio solo recibe el `reservaId`.

- `GET /disponibilidad/reservas/{reservaId}`: 200 con `reservaId`, `clienteId`,
  `eventoId`, `estado` (VIGENTE, CONFIRMADA, LIBERADA), `expiraEn` (UTC, con Z) y
  `detalles` (`sectorId`, y `fila` en texto y `numero`, o `cantidad`). 404 si no existe.
- `POST /disponibilidad/reservas/{reservaId}/confirmacion`: 204 (también si ya estaba
  confirmada, sin contar la venta de nuevo), 404, 409 con código `RESERVA_VENCIDA` o
  `RESERVA_LIBERADA`.
- `POST /disponibilidad/reservas/{reservaId}/liberacion`:
  - Pago rechazado: body `{"motivo":"PAGO_RECHAZADO"}`. Libera la reserva completa.
  - Devolución aprobada: body `{"motivo":"DEVOLUCION_APROBADA","devolucionId":"...",
    "detalles":[...]}` con solo los lugares devueltos. Puede ser parcial. El
    `devolucionId` evita liberar dos veces; el mismo ID con detalles distintos da 409.
  - Respuestas: 204 (repetible), 400, 404, 409.
- Un pago rechazado no libera una reserva ya confirmada (devuelve 409).
- Cuando se cancela un evento, Reservas libera sus reservas vigentes y bloquea nuevas.
  Este servicio no la llama.

### Entradas (servicio 5) — propuesta de Entradas, pendiente de confirmar detalles
- `POST /internal/entradas/emision`: idempotente por `idCompra`. Pedido con `idCompra`,
  `idCliente`, evento (id, nombre, `comienzaEn`) y una línea por lugar (sector, nombre
  del sector y fila y número). 201 emisión nueva, 200 reintento (mismas entradas), 409
  `IDEMPOTENCIA_CONFLICTO` si el `idCompra` llega con datos distintos. La respuesta
  trae, por entrada: `idEntrada`, sector, fila, número y `codigoQr`.
- `GET /internal/entradas/{id}`: devuelve `idCliente`, `estado` y `evento.comienzaEn`.
  Con eso se valida la titularidad y el plazo de 72 horas.
- `POST /internal/entradas/{id}/reintegro` y `POST /internal/entradas/{id}/devolucion`:
  pasan la entrada a en reintegro y a devuelta.
- `POST /internal/eventos/{idEvento}/entradas/reintegro`: marca las entradas de un
  evento cancelado y responde la cantidad marcada, sin los IDs.
- Orden al aprobar una devolución: entrada a en reintegro, liberar el lugar en
  Reservas, pedir el reintegro a Pagos y, con el reintegro exitoso, pasar la entrada a
  devuelta.

### Pagos (servicio 4) — contrato acordado (servicio aún sin implementar)
Servicio interno, sin encabezados X-User-*. Los campos van en inglés y los estados en
español; el cliente de este servicio debe respetar esos nombres exactos.

**Cobro**
- `POST /api/v1/payments` con encabezado obligatorio `Idempotency-Key` (400 si falta).
  Body: `{"purchaseId":"<uuid>","amount":50000.00,"paymentMethod":"CREDIT_CARD"}`.
- Respuesta: `{"paymentId","purchaseId","amount","status","paymentMethod"}` con `status`
  `APROBADO` o `RECHAZADO`. 201 si es nuevo, 200 si es repetición. Responde siempre en el
  momento: `EN_PROCESO` existe en el enum pero nunca se guarda.
- `GET /api/v1/payments/{paymentId}`: 404 si no existe.
- `GET /api/v1/payments/purchase/{purchaseId}`: 200 con una lista de pagos (vacía `[]`
  si la compra no tiene ninguno). No responde 404.
- Rechazo para pruebas: `DIGITAL_WALLET` da siempre `RECHAZADO`.

**Reintegro**
- `POST /api/v1/payments/{paymentId}/refunds` con `Idempotency-Key` obligatoria. Body:
  `{"refundRequestId":"<uuid>","amount":10000.00,"reason":"Devolución de entrada"}`.
  `reason` es obligatorio: 400 si falta o va vacío. Todo reintegro que pida este
  servicio (devolución, cancelación de evento o venta rechazada) lleva un motivo.
- Respuesta: `id`, `paymentId`, `refundRequestId`, `amount`, `status` (`EXITOSO` o
  `FALLIDO`), `reason`, `attemptCount` y fechas. 201 si es nuevo, 200 si es repetición.
- `POST /api/v1/refunds/{refundId}/retry` (sin cuerpo, sin clave; solo si está `FALLIDO`,
  si no 409) y `GET /api/v1/refunds/{id}`.
- Pago aprobado y reserva vencida: Pagos lo trata como un reintegro común por el total
  cobrado. Este servicio genera su propio `refundRequestId` y su propia clave, usa un
  motivo como "Reserva vencida" y lleva su propio registro de reintegros pendientes.

## Riesgos y decisiones abiertas

- **Pago aprobado pero venta rechazada:** en este cambio solo se evita emitir las
  entradas y no se confirma la compra. Pagos ya confirmó que acepta un reintegro común
  por el total, así que el reintegro es viable. Falta decidir en este servicio si se
  hace de forma automática y la compra pasa a fallida (recomendado). Mientras tanto, la
  compra queda pendiente.
- **Retención de la clave de idempotencia:** falta decidir cuánto tiempo se recuerda.
- **Pagos sin implementar:** el contrato está cerrado pero el servicio no existe todavía.
  Hasta que publique su colección de Postman, se prueba con dobles de prueba.
- **Base de datos de Eventos:** la consigna pide base relacional por microservicio en la
  Entrega 1 y NoSQL en la Entrega 2.
- **Formato de errores con Entradas:** Entradas propuso otro formato. Falta confirmar
  que use el del grupo.
- **Datos de prueba:** falta que Eventos publique IDs fijos de evento y sectores para
  que coincidan entre servicios.
- **Puerto del Gateway:** no está definido.
