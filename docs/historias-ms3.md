# TicketYa — Microservicio 3: Compras y Devoluciones (User Stories)

Versión actualizada con los contratos acordados con los servicios 1, 2, 4 y 5. Para el detalle técnico de la implementación, ver `openspec/changes/archive/2026-11-08-compras-y-pagos/design.md` y `openspec/changes/archive/2026-11-08-devoluciones/design.md`. El diagrama de secuencia de la compra y el entidad-relación están en [`diagramas-ms3.md`](diagramas-ms3.md) (fuentes e imágenes en [`diagramas-ms3/`](diagramas-ms3)), y el código del servicio, en [`Backend/mcsv-compras-devoluciones/`](../Backend/mcsv-compras-devoluciones).

## Alcance del servicio

El microservicio 3 administra las compras de los clientes y las solicitudes de devolución, y coordina ambos procesos con los otros cuatro servicios. Las historias están tomadas del documento de features y user stories de la Entrega 1 y alineadas con el documento «Arquitectura de microservicios – TicketYa».

### Qué hace

- Inicia compras a partir de una reserva y calcula su importe.
- Coordina el cobro y, según el resultado, la confirmación de la venta o la liberación de los lugares.
- Registra solicitudes de devolución y las valida; un administrador las aprueba o rechaza.
- Coordina los reintegros, incluidos los de un evento cancelado, y permite reintentarlos sin duplicar.

### Qué no hace

- No crea ni controla reservas ni lugares (servicio 2).
- No procesa cobros ni reintegros: los pide al servicio de pagos (servicio 4).
- No emite entradas ni administra sus estados: lo pide al servicio de entradas (servicio 5).
- No guarda eventos ni precios: los consulta al servicio de eventos (servicio 1).

**Datos propios:** compras, detalles de compra (con el precio unitario de cada entrada como referencia histórica), claves de idempotencia, solicitudes de devolución y reintegros. Los demás servicios se referencian solo por ID, sin claves foráneas.

| Servicio | Qué le pide este servicio |
| --- | --- |
| 1 · Lugares, sectores y eventos | Precios de los sectores y fecha de inicio del evento. Recibe de él el aviso de cancelación. |
| 2 · Disponibilidad y reservas | Estado de una reserva; confirmar la venta; liberar lugares. |
| 4 · Pagos | Procesar un cobro; procesar un reintegro; reintentar un reintegro fallido; consultar un pago. |
| 5 · Entradas | Emitir entradas; consultar titular, estado, evento y sector de una entrada; pasar a en reintegro y a devuelta. |

## Identificadores y convenciones

- `compraId` y `devolucionId`: UUID, los genera este servicio. El `compraId` se envía a Pagos como `purchaseId`.
- Reserva, entrada, pago y reintegro de Pagos: UUID, los definen sus servicios.
- Evento y sector: texto (el servicio de eventos usa MongoDB).
- Un lugar se identifica por sector, fila y número, ambos enteros (por ejemplo, fila 10, número 1). En sectores no numerados no hay fila ni número.
- El detalle de compra guarda una fila por entrada, nunca una cantidad.
- Errores de todos los servicios: `{timestamp, status, error, mensaje, campos?}`; el código de error va en `error`.
- Usuario y rol llegan en los encabezados `X-User-Id` y `X-User-Role` (sin autenticación en la Entrega 1).
- Puertos: 8080 Reservas, 8081 Eventos, 8083 Compras, 8084 Pagos, 8085 Entradas. El del Gateway no está definido.

## Estados

Este servicio maneja tres máquinas de estado independientes: la de la compra, la de la solicitud de devolución y la del reintegro. La solicitud y el reintegro no se mezclan: una devolución puede estar aprobada con su reintegro fallido.

| Entidad | Estado | Significado y transición |
| --- | --- | --- |
| Compra | Pendiente | Compra iniciada cuyo pago todavía no se resolvió, o pagada con las entradas aún sin emitir. Estado inicial. |
| Compra | Confirmada | Pago aprobado, venta confirmada y entradas emitidas. Desde pendiente. |
| Compra | Fallida | Pago rechazado y lugares liberados; pago aprobado con la venta rechazada por Reservas y reintegro automático por el total; o compra pendiente cerrada por la cancelación de su evento (con reintegro por el total si estaba cobrada). Desde pendiente. |
| Solicitud de devolución | Pendiente | Registrada por el cliente, a la espera de un administrador. Estado inicial. |
| Solicitud de devolución | Aprobada | Un administrador la aprobó. Desde pendiente; no vuelve a resolverse. |
| Solicitud de devolución | Rechazada | Un administrador la rechazó con un motivo. Desde pendiente; no vuelve a resolverse. |
| Reintegro | Pendiente | Registrado en este servicio, sin respuesta de Pagos todavía. Solo dura entre el registro y la respuesta. |
| Reintegro | Exitoso | El servicio de pagos lo confirmó; permite pasar la entrada a devuelta. |
| Reintegro | Fallido | El servicio de pagos no lo logró; se reintenta sin duplicar. |

### Estados de otros servicios que este servicio consulta o provoca

| Entidad (servicio) | Estados | Lo que importa a este servicio |
| --- | --- | --- |
| Reserva (2) | Vigente, confirmada, liberada | Solo se paga una reserva vigente; al aprobarse el pago pasa a confirmada; si el pago falla, a liberada. |
| Entrada (5) | Vigente, en reintegro, devuelta | Solo una entrada vigente admite una solicitud de devolución. Al aprobarse pasa a en reintegro; con el reintegro exitoso, a devuelta. |
| Pago (4) | Aprobado, rechazado | Define si la compra se confirma o falla. Pagos responde siempre en el momento; nunca informa un pago en proceso. |
| Reintegro (4) | Exitoso, fallido | Pagos termina cada reintegro como exitoso o fallido en la misma llamada. |

## Compras

El cliente inicia la compra con el identificador de una reserva que ya creó en el servicio de disponibilidad y reservas. Orden del flujo de pago:

1. Validar que la reserva es del cliente y consultar los precios.
2. Registrar la compra como pendiente.
3. Consultar el estado de la reserva (verificación preventiva).
4. Pedir el cobro al servicio de pagos.
5. Si el pago se aprueba: guardar el identificador del pago, pedir la confirmación de la venta al servicio de disponibilidad y reservas y, una vez confirmada, pedir la emisión de las entradas. La compra queda confirmada. Si Reservas rechaza la venta, la compra queda fallida y se pide un reintegro automático por el total.
6. Si el pago se rechaza: pedir la liberación de los lugares. La compra queda fallida.

### C-1 · Iniciar una compra desde una reserva

**Origen:** US-4.3. **Como** cliente, **quiero** comprar a partir de una reserva vigente, **para** pagar los lugares que retuve.

- **Dado** una reserva vigente que me pertenece, **cuando** inicio la compra indicando su identificador, **entonces** el servicio consulta los precios de los sectores al servicio de eventos, calcula el importe total y registra la compra en estado pendiente, con el detalle de los lugares (una fila por entrada) y el precio unitario de cada entrada.
- **Dado** una reserva que pertenece a otro cliente, **cuando** intento comprarla, **entonces** el sistema lo rechaza.
- **Dado** un identificador de reserva inexistente, **cuando** intento comprar, **entonces** recibo un error de "reserva no encontrada".

### C-2 · Verificar la reserva antes de cobrar

**Origen:** US-4.3. **Como** cliente, **quiero** que no me cobren una reserva que ya venció, **para** no pagar por lugares que perdí.

- **Dado** una compra pendiente, **cuando** se va a solicitar el cobro, **entonces** el servicio consulta antes el estado de la reserva al servicio de disponibilidad y reservas.
- **Dado** una reserva vencida o liberada, **cuando** se hace esa consulta, **entonces** no se pide el cobro y el sistema me indica que debo reservar de nuevo.

Esta verificación es preventiva: la confirmación de la venta (C-3) es la validación definitiva.

### C-3 · Pago aprobado: confirmar la venta y emitir las entradas

**Origen:** US-4.3. **Como** cliente, **quiero** que al aprobarse mi pago se confirme la compra y reciba mis entradas, **para** poder ingresar al evento.

- **Dado** una compra pendiente con la reserva vigente, **cuando** el servicio de pagos aprueba el pago, **entonces** el servicio guarda el identificador del pago, pide la confirmación de la venta al servicio de disponibilidad y reservas y, una vez emitidas las entradas, la compra queda confirmada.
- **Dado** una venta confirmada, **cuando** el servicio pide emitir las entradas, **entonces** el servicio de entradas genera una por cada lugar comprado, cada una con un código QR único, y el servicio guarda el identificador de cada entrada en el detalle.
- **Dado** que el servicio de disponibilidad y reservas rechaza la confirmación (la reserva venció o se liberó), **cuando** ocurre, **entonces** la compra queda fallida, no se piden entradas y el servicio pide a Pagos un reintegro automático por el total cobrado, con un identificador de solicitud propio. Si el reintegro falla o no tiene respuesta, la compra queda fallida igual y el reintegro queda registrado para reintentarlo.
- **Dado** que el pago está aprobado y la venta confirmada pero el servicio de entradas falla, **cuando** se agotan 3 intentos de emisión, **entonces** la compra queda pendiente y no se vuelve a cobrar.
- **Dado** una compra pagada cuya emisión falló, **cuando** el cliente repite el pago con la misma clave, **entonces** el servicio solo repite la emisión, sin cobrar ni confirmar la venta de nuevo.

### C-4 · Pago rechazado: liberar los lugares

**Origen:** US-4.4. **Como** cliente, **quiero** que si mi pago falla los lugares queden libres, **para** que otros puedan comprarlos.

- **Dado** que el servicio de pagos rechaza el pago, **cuando** el servicio recibe la respuesta, **entonces** registra la compra como fallida y pide al servicio de disponibilidad y reservas liberar los lugares.
- **Dado** una compra fallida, **cuando** la consulto, **entonces** figura con ese estado y sin entradas asociadas.

El rechazo de Pagos llega como una respuesta normal con estado "rechazado", no como un error técnico.

### C-5 · Reintentar un pago sin duplicar la compra

**Origen:** US-5.3. **Como** cliente, **quiero** poder reintentar el pago si se cortó mi conexión, **para** no quedar cobrado dos veces ni recibir entradas duplicadas.

- **Dado** un pedido de pago con una clave de idempotencia (una por operación de pago), **cuando** se repite con la misma clave, **entonces** el servicio devuelve el resultado original sin cobrar ni pedir entradas de nuevo.
- **Dado** un reintento mientras el primer pedido sigue en proceso, **cuando** llega, **entonces** el servicio no inicia un segundo cobro e informa que la operación está en curso.
- **Dado** una misma clave con datos distintos a los del primer pedido, **cuando** llega, **entonces** el servicio la rechaza.
- **Dado** dos clientes que usan el mismo valor de clave, **cuando** llegan sus pedidos, **entonces** cada uno se trata por separado: la clave solo vale para el cliente que la envió.
- **Dado** un medio de pago que no es crédito, débito, transferencia ni billetera digital, **cuando** se pide el pago, **entonces** el servicio lo rechaza sin llamar a Pagos.
- **Dado** una compra que ya tiene un pago aprobado, **cuando** se repite el pago, **entonces** no se pide un segundo cobro.
- **Dado** que no se recibió la respuesta de Pagos (corte o caída), **cuando** el cliente repite el pago, **entonces** el servicio consulta los pagos de esa compra en Pagos: si hay un pago aprobado, lo usa; si no hay ninguno aprobado (lista vacía o solo rechazados), repite el cobro con la misma clave de cobro, que es seguro. Si el intento anterior había sido rechazado, Pagos devuelve ese rechazo y la compra queda fallida.

### C-6 · Consultar mis compras

**Origen:** US-4.6. **Como** cliente, **quiero** ver mis compras, **para** conocer su estado y su detalle.

- **Dado** un cliente con compras, **cuando** consulta sus compras, **entonces** ve solo las suyas, con evento, sector, lugares adquiridos, precio unitario de cada entrada, total, medio de pago, fecha y estado (pendiente, confirmada o fallida).
- **Dado** una compra de otro cliente, **cuando** intento consultarla, **entonces** el servicio me deniega el acceso.
- **Dado** una compra ya registrada, **cuando** la consulto, **entonces** los precios mostrados son los vigentes al momento de la compra.

## Devoluciones

El cliente solicita la devolución de una entrada hasta 72 horas antes del inicio del evento. Un administrador la aprueba o la rechaza. Al aprobarla, el servicio coordina tres operaciones en otros servicios y deja registrado el estado del reintegro.

### D-1 · Solicitar la devolución de una entrada

**Origen:** US-6.1. **Como** cliente, **quiero** solicitar la devolución de una entrada, **para** recuperar mi dinero si no puedo asistir.

- **Dado** una entrada vigente propia y un evento que empieza en más de 72 horas, **cuando** solicito la devolución, **entonces** el servicio valida la titularidad y el estado de la entrada con el servicio de entradas y la fecha de inicio con el servicio de eventos, y registra una solicitud en estado pendiente con un `devolucionId` (UUID).
- **Dado** un evento que empieza en 72 horas o menos, **cuando** solicito la devolución, **entonces** el servicio la rechaza indicando que venció el plazo.
- **Dado** una entrada que ya no está vigente (en reintegro o devuelta) o que pertenece a otro cliente, **cuando** solicito la devolución, **entonces** el servicio la rechaza.
- **Dado** una solicitud pendiente para una entrada, **cuando** solicito otra para la misma entrada, **entonces** el servicio no crea un duplicado.

### D-2 · Aprobar o rechazar una devolución

**Origen:** US-6.2. **Como** administrador, **quiero** aprobar o rechazar las solicitudes de devolución, **para** controlar los reintegros.

- **Dado** una solicitud pendiente, **cuando** un administrador la aprueba, **entonces** la solicitud queda aprobada y el servicio, en este orden, (a) pide al servicio de entradas pasar la entrada a en reintegro, invalidando su QR, (b) pide al servicio de disponibilidad y reservas liberar el lugar (con el `devolucionId`, para no liberarlo dos veces) y (c) pide al servicio de pagos el reintegro del precio unitario de esa entrada.
- **Dado** una solicitud pendiente, **cuando** el administrador la rechaza indicando un motivo, **entonces** la solicitud queda rechazada, la entrada sigue vigente y el motivo queda registrado.
- **Dado** una solicitud ya resuelta, **cuando** intento resolverla de nuevo, **entonces** el servicio lo rechaza.
- **Dado** un usuario que no es administrador, **cuando** intenta aprobar o rechazar, **entonces** el servicio le deniega el acceso.

### D-3 · Seguir mi solicitud y mi reintegro

**Origen:** US-6.3. **Como** cliente, **quiero** conocer el estado de mi solicitud de devolución y de mi reintegro, **para** saber cuándo recibiré el dinero.

- **Dado** un cliente con solicitudes de devolución, **cuando** las consulta, **entonces** ve solo las suyas y, por separado, el estado de cada solicitud (pendiente, aprobada o rechazada) y el de su reintegro (pendiente, exitoso o fallido).
- **Dado** una solicitud rechazada, **cuando** el cliente la consulta, **entonces** ve el motivo del rechazo.
- **Dado** la solicitud de otro cliente, **cuando** intento consultarla, **entonces** el servicio me deniega el acceso.

### D-4 · Registrar y reintentar los reintegros

**Origen:** US-6.3. **Como** equipo, **queremos** que cada reintegro quede registrado y pueda reintentarse sin duplicarse, **para** no devolver dos veces el mismo dinero.

- **Dado** una devolución aprobada, **cuando** el servicio pide el reintegro a Pagos, **entonces** registra el reintegro con el `devolucionId` como identificador de la solicitud y un motivo (`reason`, obligatorio en Pagos: sin motivo responde 400) y, con el reintegro exitoso, pide al servicio de entradas pasar la entrada a devuelta.
- **Dado** que Pagos no logra el reintegro, **cuando** se intenta, **entonces** el reintegro queda fallido y la entrada permanece en reintegro hasta que pueda reintentarse.
- **Dado** un reintegro fallido, **cuando** se reintenta, **entonces** se usa el reintento de Pagos sobre el mismo reintegro y nunca un identificador de solicitud nuevo, para que no se duplique.
- **Dado** un reintento que Pagos rechaza porque el reintegro ya no está fallido, **cuando** ocurre, **entonces** el servicio consulta el estado del reintegro, porque puede que ya haya salido bien.
- **Dado** que Pagos acepta varios reintegros parciales de un mismo pago mientras la suma no supere lo cobrado, **cuando** se devuelven entradas de una misma compra por separado, **entonces** cada una genera su propio reintegro por su precio.
- **Dado** un reintento del paso (a) o (b) de D-2 que ya se había aplicado, **cuando** se repite, **entonces** no genera cambios duplicados (el servicio de entradas no vuelve a pasar a en reintegro una entrada ya devuelta).

## Cancelación de eventos

Cuando un administrador cancela un evento, el servicio de eventos avisa a este servicio. La devolución de las compras confirmadas es automática: no hay solicitudes individuales ni aprobación.

### E-1 · Devolver todas las compras de un evento cancelado

**Origen:** US-6.4. **Como** cliente, **quiero** que si el evento se cancela se me reintegre lo pagado, **para** no perder mi dinero.

- **Dado** un evento que se cancela, **cuando** el servicio de eventos notifica la cancelación, **entonces** el servicio coordina la devolución de todas las compras confirmadas de ese evento, sin requerir aprobación del administrador.
- **Dado** una compra confirmada de ese evento, **cuando** se coordina su devolución, **entonces** el servicio pide al servicio de entradas pasar las entradas del evento a en reintegro y al servicio de pagos un único reintegro por la compra, con un identificador de solicitud propio que genera este servicio (también es la clave de idempotencia) y `reason` "Evento cancelado", que Pagos exige.
- **Dado** una compra confirmada con alguna entrada que ya tiene una devolución aprobada, **cuando** se coordina su devolución, **entonces** el monto del reintegro es solo el precio unitario de las entradas que seguían vigentes; si no queda ninguna, no se pide reintegro.
- **Dado** una solicitud de devolución pendiente de una entrada del evento, **cuando** se procesa la cancelación, **entonces** la solicitud queda rechazada con el motivo "Evento cancelado: reintegro automático" y su entrada entra en el reintegro por la cancelación.
- **Dado** una notificación de cancelación recibida más de una vez, **cuando** se procesa de nuevo, **entonces** no se duplican los reintegros: se responde 200 si el evento ya se procesó y 202 si todavía se está procesando. Aunque la marcación de Entradas responda `entradasMarcadas: 0`, no se pide otro reintegro por las compras que ya tienen el suyo.
- **Dado** una cancelación que quedó en proceso porque el servicio se cayó después de responder 202 (o porque una compra falló), **cuando** pasa el plazo (`compras.cancelacion.plazo-en-proceso`, 5 minutos), **entonces** el servicio la retoma solo, sin esperar otro aviso de Eventos (que no reenvía el aviso después de un 202): no duplica reintegros ni vuelve a pedir a Entradas una marcación ya hecha. Si un aviso repetido llega a la vez, la toma es atómica y solo uno de los dos la procesa.
- **Dado** una compra pendiente de ese evento con un pago aprobado (por ejemplo, porque falló la emisión de entradas), **cuando** se procesa la cancelación, **entonces** la compra pasa a fallida con un cambio de estado condicional y, solo si ese cambio se aplica, se pide un único reintegro por el total, con `reason` "Evento cancelado" y un identificador de solicitud propio guardado antes de llamar a Pagos. Los fallos se reintentan como cualquier reintegro.
- **Dado** una compra pendiente de ese evento sin pago registrado, **cuando** se procesa la cancelación, **entonces** el servicio consulta a Pagos los pagos de la compra: si hay uno aprobado (el cobro se hizo pero se perdió la respuesta), guarda ese pago y sigue como en el caso anterior; si no hay ninguno aprobado, la compra pasa a fallida sin reintegro. Si Pagos no responde, la compra sigue pendiente y la cancelación se retoma automáticamente pasado el plazo.
- **Dado** un pago de esa compra que se está retomando al mismo tiempo, **cuando** se procesa la cancelación, **entonces** gana quien cambia primero el estado de la compra: un pago retomado nunca confirma una compra ya fallida, y una compra que llegó a confirmarse sigue el camino normal de la cancelación.
- **Dado** una compra que ya estaba fallida, **cuando** se procesa la cancelación, **entonces** no se pide ningún reintegro por ella.
- **Límite aceptado en la Entrega 1:** si la emisión se hizo en Entradas pero se perdió la respuesta, esas entradas quedan en reintegro sin pasar a devueltas, porque este servicio no conoce su `idEntrada`.

### E-2 · Reintentar los reintegros pendientes de una cancelación

**Origen:** US-6.4. **Como** equipo, **queremos** registrar los reintegros por cancelación que no se completaron y poder reintentarlos, **para** que ningún cliente quede sin su dinero por una falla puntual.

- **Dado** un reintegro por cancelación que falla, **cuando** ocurre, **entonces** queda registrado como fallido y el resto de las devoluciones del evento sigue su curso.
- **Dado** un reintegro registrado como fallido, **cuando** un administrador lo reintenta con `POST /reintegros/{reintegroId}/reintento` (el reintento de Pagos), **entonces** no se duplica y, si se confirma, las entradas que incluye pasan a devuelta.
- **Dado** un cliente con entradas de un evento cancelado, **cuando** consulta sus compras o devoluciones, **entonces** ve el estado de su reintegro.

## Permisos y requisitos técnicos

El API Gateway identifica al usuario con los encabezados `X-User-Id` y `X-User-Role` y los propaga; este servicio valida los permisos de sus propias operaciones.

| ID | Historia | Criterio de aceptación |
| --- | --- | --- |
| P-1 | Como cliente, quiero que solo yo vea mis compras, para proteger mi información. | Un cliente que consulta compras o solicitudes de otro recibe acceso denegado (C-6, D-3). |
| P-2 | Como administrador, quiero ser el único que resuelva devoluciones, para controlar los reintegros. | Un usuario que no es administrador no puede aprobar ni rechazar solicitudes (D-2). |
| P-3 | Como cliente, quiero que solo yo pueda comprar mis reservas, para que nadie pague por mí sin permiso. | Una compra a partir de una reserva ajena se rechaza (C-1). |
| P-4 | Como equipo, queremos que este servicio tenga su propia base relacional, para mantener los servicios desacoplados. | Guarda compras, detalles, claves de idempotencia, solicitudes y reintegros; no accede a tablas de otros servicios; se relaciona con ellos por ID y por APIs REST. |
| P-5 | Como equipo, queremos que las llamadas a otros servicios sean comunicación directa entre servicios, para no exponerlas en el gateway. | Las operaciones internas (confirmar venta, liberar, emitir, cobrar, reintegrar) no pasan por el API Gateway. |
| P-6 | Como equipo, queremos pruebas unitarias de la lógica de compra y devolución, para detectar errores temprano. | Hay pruebas de cada escenario de C-1 a E-2 que corren con los otros servicios reemplazados por dobles de prueba. |
| P-7 | Como equipo, queremos documentar los endpoints con OpenAPI, para que sean claros y probables. | Cada endpoint tiene método, ruta, parámetros, códigos de respuesta, ejemplos JSON y códigos de error. |

## Endpoints de este servicio

| Método y ruta | Quién | Descripción | Respuestas |
| --- | --- | --- | --- |
| `POST /compras` | Cliente | Inicia una compra. Body: `{"reservaId":"..."}`. Si el cliente repite el pedido con la compra pendiente, recibe esa compra (200) | 201, 200, 403, 404, 409, 503 |
| `POST /compras/{compraId}/pago` | Cliente | Paga. Encabezado `Idempotency-Key`; body: `{"medioDePago":"..."}` | 200, 202, 400, 403, 404, 409, 503 |
| `GET /compras` | Cliente | Lista sus compras | 200 |
| `GET /compras/{compraId}` | Cliente | Detalle de una compra propia, con sus reintegros | 200, 403, 404 |
| `POST /devoluciones` | Cliente | Solicita la devolución de una entrada. Body: `{"entradaId":"..."}`. Si ya tenía una pendiente para esa entrada, la recibe (200) | 201, 200, 400, 403, 404, 409, 503 |
| `GET /devoluciones` | Cliente o administrador | El cliente ve las suyas; el administrador, todas. Filtro `?estado=` | 200 |
| `GET /devoluciones/{devolucionId}` | Titular o administrador | Detalle con el estado de la solicitud y, por separado, el del reintegro | 200, 403, 404 |
| `POST /devoluciones/{devolucionId}/aprobacion` | Administrador | Aprueba y coordina la devolución. 202 si un paso quedó pendiente | 200, 202, 403, 404, 409 |
| `POST /devoluciones/{devolucionId}/rechazo` | Administrador | Rechaza con un motivo. Body: `{"motivo":"..."}` | 200, 400, 403, 404, 409 |
| `GET /reintegros` | Administrador | Lista los reintegros. Filtro `?estado=` | 200, 403 |
| `POST /reintegros/{reintegroId}/reintento` | Administrador | Reintenta sin duplicar; en una devolución, la retoma desde el paso que falló | 200, 202, 403, 404 |
| `POST /eventos-cancelados` | Eventos (interno) | Aviso de cancelación. Idempotente por `eventoId`; procesa en segundo plano | 200, 202, 400 |

El administrador se identifica con `X-User-Role: ADMIN` (confirmado por el grupo; el valor es configurable).

## Contratos con los otros servicios

### Eventos (1) — confirmado

- `GET /internal/eventos/{eventoId}`: `eventoId`, `nombre`, `fechaInicio` (ISO-8601, UTC), `estado` (en minúsculas: `borrador`, `publicado`, `agotado`, `cancelado` o `finalizado`) y `sectores` (`sectorId`, `nombre`, `precio`). 404 si no existe. Llamada directa, sin Gateway.
- `POST /eventos-cancelados` (Eventos llama a este servicio): `eventoId`, `motivo`, `canceladoEn`. 202 si lo acepta, 200 si ya lo procesó.

### Reservas (2) — confirmado

El cliente crea la reserva en Reservas (por el Gateway, con un `solicitudId` para no duplicarla); este servicio solo recibe el `reservaId`.

- `GET /disponibilidad/reservas/{reservaId}`: `reservaId`, `clienteId`, `eventoId`, `estado` (VIGENTE, CONFIRMADA, LIBERADA), `expiraEn` (UTC) y `detalles` (`sectorId`, `fila` y `numero` enteros, o `cantidad`).
- `POST /disponibilidad/reservas/{reservaId}/confirmacion`: 204 (repetible), 404, 409 con `RESERVA_VENCIDA` o `RESERVA_LIBERADA`.
- `POST /disponibilidad/reservas/{reservaId}/liberacion`: con `PAGO_RECHAZADO` libera la reserva completa; con `DEVOLUCION_APROBADA` lleva `devolucionId` y los `detalles` devueltos (puede ser parcial). 204 (repetible), 400, 404, 409.

### Pagos (4) — contrato acordado, servicio aún sin implementar

Servicio interno, sin encabezados de usuario. Los campos van en inglés y los estados en español.

**Cobro**

- `POST /api/v1/payments` con `Idempotency-Key` obligatoria y distinta del `purchaseId`. Body: `{"purchaseId":"<uuid>","amount":50000.00,"paymentMethod":"CREDIT_CARD"}`.
- Respuesta: `paymentId`, `purchaseId`, `amount`, `status` (`APROBADO` o `RECHAZADO`), `paymentMethod`. 201 si es nuevo, 200 si es repetición.
- Un rechazo es una respuesta normal (201 con `RECHAZADO`). El 409 queda para conflictos: misma clave con datos distintos, o clave nueva sobre una compra ya pagada.
- Tras un rechazo, un nuevo intento necesita una clave nueva.
- `GET /api/v1/payments/{paymentId}`: 404 si no existe.
- `GET /api/v1/payments/purchase/{purchaseId}`: 200 con la lista de pagos de la compra (vacía `[]` si no tiene ninguno; no responde 404). Este servicio solo tiene en cuenta el pago aprobado.
- Medios: `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`. Sin moneda (pesos), monto positivo con hasta 2 decimales.
- Para pruebas, `DIGITAL_WALLET` da siempre `RECHAZADO`.

**Reintegro**

- `POST /api/v1/payments/{paymentId}/refunds` con `Idempotency-Key` obligatoria. Body: `{"refundRequestId":"<uuid>","amount":10000.00,"reason":"..."}`. `reason` es obligatorio: 400 si falta o va vacío.
- Respuesta: `id`, `paymentId`, `refundRequestId`, `amount`, `status` (`EXITOSO` o `FALLIDO`), `reason`, `attemptCount` y fechas. 201 si es nuevo, 200 si es repetición.
- Solo sobre pagos aprobados (si no, 409). Acepta varios parciales mientras la suma no supere lo cobrado; los fallidos no descuentan saldo. El mismo `refundRequestId` con otra clave da 409.
- Reintento: `POST /api/v1/refunds/{refundId}/retry`, sin cuerpo ni clave; solo si está fallido (si no, 409). Consulta: `GET /api/v1/refunds/{id}`.

### Entradas (5) — confirmado

- `POST /internal/entradas/emision`: idempotente por `idCompra`. Pedido: `idCompra`, `idCliente`, `evento` (`id`, `nombre`, `comienzaEn`) y `lugares`, una línea por lugar con `idSector`, `nombreSector`, `fila` y `numero` (enteros). En un sector no numerado va una línea por lugar con `fila` y `numero` en `null`, y la respuesta los conserva. 201 emisión nueva, 200 reintento, 409 `IDEMPOTENCIA_CONFLICTO`. Devuelve por entrada `idEntrada`, `idSector`, `fila`, `numero` y `codigoQr`. Una fila que no es un número se trata como error de contrato.
- `GET /internal/entradas/{id}`: `idEntrada`, `codigoQr`, `estado`, `evento` (`id`, `nombre`, `comienzaEn`), `sector` anidado (`id`, `nombre`), `fila`, `numero`, `idCompra` e `idCliente`. El sector se toma de `sector.id` y `sector.nombre`. Si no existe: 404 `ENTRADA_NO_ENCONTRADA`; un 404 sin ese código se trata como falla del servicio.
- `POST /internal/entradas/{id}/reintegro` y `POST /internal/entradas/{id}/devolucion`: 200, también al repetirse. Una transición inválida responde 409 `TRANSICION_INVALIDA`, que este servicio registra como conflicto de estado de la entrada, distinto de una caída de Entradas: el paso queda pendiente y un administrador lo retoma.
- Errores: Entradas adopta el formato del grupo, con el código de negocio en `error`. Mientras termina el cambio, este servicio también lo lee de `codigo`.
- `fila` es un entero, como en el resto de los servicios.
- `POST /internal/eventos/{idEvento}/entradas/reintegro`: pasa a en reintegro las entradas vigentes de un evento cancelado y responde cuántas marcó en `entradasMarcadas`, sin sus IDs. No formó parte de la confirmación: se mantiene como en la propuesta (repetirla responde `entradasMarcadas: 0`). El dato es solo informativo: el monto de cada reintegro se calcula con los datos de este servicio.

## Decisiones tomadas y puntos abiertos

### Resueltos

| Tema | Decisión |
| --- | --- |
| Pago aprobado y reserva vencida (técnica) | Pagos lo trata como un reintegro común por el total cobrado. Este servicio genera su propio identificador de solicitud y su propia clave, y lleva su registro de reintegros pendientes. |
| Reintegro parcial | Pagos admite parciales; el reintegro de una devolución es el precio unitario de la entrada. |
| Cómo se reintentan los reintegros | Con el reintento de Pagos sobre el mismo reintegro, sin crear un identificador nuevo. |
| Compras que quedan pendientes | Al repetir el pago se consulta a Pagos el pago de esa compra; no hay tarea periódica en la Entrega 1. |
| Clave de idempotencia | Encabezado `Idempotency-Key`, acotada por cliente. A Pagos se envía una clave de cobro generada por este servicio. |
| Orden al aprobar una devolución | Entrada a en reintegro, liberar el lugar, pedir el reintegro y, con el reintegro exitoso, entrada a devuelta. |
| Consulta de pagos por compra | Pagos devuelve una lista (200, vacía si no hay pagos). Se usa solo el pago aprobado; si no hay, se repite el cobro con la misma clave de cobro. |
| Rechazo para pruebas | `DIGITAL_WALLET` da siempre `RECHAZADO`. |
| Motivo del reintegro | `reason` es obligatorio en Pagos (400 si falta o va vacío): toda devolución y todo reintegro por cancelación lleva un motivo. |
| Pago aprobado pero venta rechazada | Reintegro automático por el total y la compra pasa a fallida. El reintegro se crea solo al pasar la compra a fallida, así que nunca hay dos por compra. |
| Fallos parciales al aprobar una devolución | Se registra y se retoma, sin revertir: la devolución queda aprobada, guarda qué pasos se hicieron y un administrador la reintenta desde el que falló. No se revierte porque Reservas y Entradas no tienen operaciones inversas. Cada paso es repetible sin duplicar efectos. |
| Tipo de la fila | La fila de un lugar es un entero en todos los servicios (confirmado por Reservas). Una fila no numérica recibida de otro servicio es un error de contrato (502 `CONTRATO_INVALIDO`). |
| Rol de administrador | `X-User-Role: ADMIN`, confirmado por el grupo. |
| Cancelación de un evento | Aviso idempotente por `eventoId` (202 aceptado o en proceso, 200 ya procesado). Un reintegro por compra confirmada, con `reason` "Evento cancelado" y el precio de las entradas vigentes. Los reintegros fallidos los reintenta un administrador, como en las devoluciones. Una cancelación interrumpida se retoma automáticamente: una revisión periódica (`compras.cancelacion.intervalo-reanudacion`, 60 s; se desactiva con `compras.cancelacion.reanudacion-activa=false`) toma con una actualización condicional las que llevan en proceso más que el plazo. |
| Compra pendiente ya cobrada en un evento cancelado | La cancelación cierra las compras pendientes del evento: pasan a fallidas con un cambio de estado condicional y, si tienen un pago aprobado (registrado o informado por Pagos), reciben un único reintegro por el total. Si un pago se retoma a la vez, gana quien cambia primero el estado. Límite aceptado en la Entrega 1: las entradas emitidas cuya respuesta se perdió quedan en reintegro sin pasar a devueltas. |
| Retención de la clave de idempotencia | No vence en la Entrega 1: se conserva mientras exista la compra. Es seguro porque antes de cobrar se revisa si la compra ya tiene un pago, se consulta a Pagos si otra clave ya intentó pagarla y Pagos rechaza (409) un segundo cobro de una compra ya pagada, así que repetir un pago con una clave olvidada no cobraría dos veces. Una limpieza agregaría código y pruebas sin sumar seguridad; en la Entrega 2 puede sumarse una limpieza programada (por ejemplo, 24 horas después de completarse). |
| Contrato de Entradas | Confirmado por Entradas: pedido de emisión con `lugares` (`idSector`, `nombreSector`, `fila`, `numero`), consulta con el sector anidado, cambios de estado repetibles (200) y 409 `TRANSICION_INVALIDA` como conflicto de estado. Código de error en `error` (o en `codigo` mientras Entradas migra). |
| Estado de un evento | Eventos lo envía en minúsculas: `borrador`, `publicado`, `agotado`, `cancelado` o `finalizado`. Una entrada de un evento `cancelado` no admite devolución (409 `EVENTO_CANCELADO`). |

### Abiertos

1. **Reservas:** la forma de un lugar de sector no numerado en la liberación por devolución (se asume `{"sectorId","cantidad":1}`).
2. **Eventos:** IDs fijos de evento y sectores para los datos de prueba.
3. **Gateway:** puerto sin definir.

## Cómo se organiza en OpenSpec

Tres cambios, en este orden. Cada criterio Dado / Cuando / Entonces de este documento se convierte en un escenario WHEN / THEN de la spec.

| Orden | Cambio | Historias | Por qué en este lugar |
| --- | --- | --- | --- |
| 1 | `compras-y-pagos` | C-1 a C-6, P-1, P-3 | Camino principal; fija el modelo de compra y la idempotencia. También deja el modelo de datos de los reintegros. |
| 2 | `devoluciones` | D-1 a D-4, P-2 | Necesita compras confirmadas y define la máquina de estados del reintegro. |
| 3 | `cancelacion-de-eventos` | E-1, E-2 | Reutiliza la coordinación de reintegros del cambio anterior, sin aprobación individual. |
