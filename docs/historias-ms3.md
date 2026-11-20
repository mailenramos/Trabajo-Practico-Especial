# TicketYa — Microservicio 3: Compras y Devoluciones (User Stories)

Versión actualizada con los contratos acordados con los servicios 1, 2, 4 y 5, y con las historias de la Entrega 2 (Recomendaciones, modificación de eventos y avisos a Notificaciones). Para el detalle técnico de la implementación, ver `openspec/changes/archive/2026-11-08-compras-y-pagos/design.md`, `openspec/changes/archive/2026-11-08-devoluciones/design.md` y, para la Entrega 2, `openspec/changes/compras-entrega-2/design.md`. El diagrama de secuencia de la compra y el entidad-relación están en [`diagramas-ms3.md`](diagramas-ms3.md) (fuentes e imágenes en [`diagramas-ms3/`](diagramas-ms3)), y el código del servicio, en [`Backend/mcsv-compras-devoluciones/`](../Backend/mcsv-compras-devoluciones).

## Alcance del servicio

El microservicio 3 administra las compras de los clientes y las solicitudes de devolución, y coordina ambos procesos con los otros cuatro servicios. Las historias están tomadas del documento de features y user stories de la Entrega 1 y alineadas con el documento «Arquitectura de microservicios – TicketYa».

### Qué hace

- Inicia compras a partir de una reserva y calcula su importe.
- Coordina el cobro y, según el resultado, la confirmación de la venta o la liberación de los lugares.
- Registra solicitudes de devolución y las valida; un administrador las aprueba o rechaza.
- Coordina los reintegros, incluidos los de un evento cancelado, y permite reintentarlos sin duplicar.
- (Entrega 2) Aplica los cambios de fecha, horario o lugar de un evento a sus compras y avisa a los compradores.
- (Entrega 2) Avisa a Notificaciones cada cambio relevante de una compra, devolución o reintegro, sin bloquear ni revertir la operación.
- (Entrega 2) Le da a Recomendaciones dos consultas sobre las compras confirmadas.

### Qué no hace

- No crea ni controla reservas ni lugares (servicio 2).
- No procesa cobros ni reintegros: los pide al servicio de pagos (servicio 4).
- No emite entradas ni administra sus estados: lo pide al servicio de entradas (servicio 5).
- No guarda eventos ni precios: los consulta al servicio de eventos (servicio 1).
- No envía correos: se los pide a Notificaciones (servicio 9).
- No calcula recomendaciones: solo le da datos de compras a Recomendaciones (servicio 8).

**Datos propios:** compras, detalles de compra (con el precio unitario de cada entrada como referencia histórica), claves de idempotencia, solicitudes de devolución, reintegros y, desde la Entrega 2, modificaciones de eventos recibidas y avisos pendientes de enviar a Notificaciones. Los demás servicios se referencian solo por ID, sin claves foráneas.

| Servicio | Qué le pide este servicio |
| --- | --- |
| 1 · Lugares, sectores y eventos | Precios de los sectores, fecha de inicio y tipo del evento. Recibe de él los avisos de cancelación y de modificación. |
| 2 · Disponibilidad y reservas | Estado de una reserva; confirmar la venta; liberar lugares. |
| 4 · Pagos | Procesar un cobro; procesar un reintegro; reintentar un reintegro fallido; consultar un pago. |
| 5 · Entradas | Emitir entradas; consultar las entradas de una compra; consultar titular, estado, evento y sector de una entrada; pasar a en reintegro y a devuelta; marcar las entradas de un evento cancelado y confirmar su cierre. |
| 8 · Preferencias y recomendaciones | Nada: es Recomendaciones quien consulta a este servicio (co-compras e historial de preferencias). |
| 9 · Notificaciones | Recibir los avisos de compras, devoluciones, reintegros y eventos cancelados o modificados. |

## Identificadores y convenciones

- `compraId` y `devolucionId`: UUID, los genera este servicio. El `compraId` se envía a Pagos como `purchaseId`.
- Reserva, entrada, pago y reintegro de Pagos: UUID, los definen sus servicios.
- Evento y sector: texto (el servicio de eventos usa MongoDB).
- Un lugar se identifica por sector, fila y número, ambos enteros (por ejemplo, fila 10, número 1). En sectores no numerados no hay fila ni número.
- El detalle de compra guarda una fila por entrada, nunca una cantidad.
- Errores de todos los servicios: `{timestamp, status, error, mensaje, campos?}`; el código de error va en `error`.
- Usuario y roles llegan en los encabezados `X-User-Id` y `X-User-Roles` (roles separados por coma), que propaga el API Gateway después de validar el JWT (Entrega 2). En la Entrega 1 era un solo rol en `X-User-Role`, que se sigue aceptando como respaldo si no llega `X-User-Roles`.
- Los endpoints `/internal/**` de la Entrega 2 los llaman otros servicios: exigen el encabezado `X-Service-Token` con el token compartido (variable `SERVICIO_TOKEN`), provisorio hasta JWT.
- Puertos: 8080 Reservas, 8081 Eventos, 8083 Compras, 8084 Pagos, 8085 Entradas, 8090 Notificaciones (a confirmar). El del Gateway no está definido.

## Estados

Este servicio maneja tres máquinas de estado independientes: la de la compra, la de la solicitud de devolución y la del reintegro. La solicitud y el reintegro no se mezclan: una devolución puede estar aprobada con su reintegro fallido.

| Entidad | Estado | Significado y transición |
| --- | --- | --- |
| Compra | Pendiente | Compra iniciada cuyo pago todavía no se resolvió, o pagada con las entradas aún sin emitir. Estado inicial. |
| Compra | Confirmada | Pago aprobado, venta confirmada y entradas emitidas. Desde pendiente. |
| Compra | Fallida | Pago rechazado y lugares liberados; pago aprobado con la venta rechazada por Reservas y reintegro automático por el total; compra pendiente cerrada por la cancelación de su evento (con reintegro por el total si estaba cobrada); o pago aprobado con la emisión rechazada por `EVENTO_CERRADO`, con reintegro automático por el total. Desde pendiente. |
| Solicitud de devolución | Pendiente | Registrada por el cliente, a la espera de un administrador. Estado inicial. |
| Solicitud de devolución | Aprobada | Un administrador la aprobó. Desde pendiente; no vuelve a resolverse. |
| Solicitud de devolución | Rechazada | Un administrador la rechazó con un motivo. Desde pendiente; no vuelve a resolverse. |
| Reintegro | Pendiente | Registrado en este servicio, sin respuesta de Pagos todavía. Solo dura entre el registro y la respuesta. |
| Reintegro | Exitoso | El servicio de pagos lo confirmó; permite pasar la entrada a devuelta. |
| Reintegro | Fallido | El servicio de pagos no lo logró; se reintenta sin duplicar. |
| Aviso a Notificaciones | Pendiente | Guardado junto con la operación, a la espera de la tarea de envío (o de su próximo intento). Estado inicial. |
| Aviso a Notificaciones | Procesando | Una ejecución de la tarea lo tomó y lo está enviando. Si se interrumpe, vence y vuelve a pendiente. |
| Aviso a Notificaciones | Enviado | Notificaciones confirmó la recepción. Desde procesando. |
| Aviso a Notificaciones | Error final | Notificaciones lo rechazó por inválido o se agotaron los intentos. No se reintenta; la operación que lo generó no cambia. |

### Estados de otros servicios que este servicio consulta o provoca

| Entidad (servicio) | Estados | Lo que importa a este servicio |
| --- | --- | --- |
| Reserva (2) | Vigente, confirmada, liberada | Solo se paga una reserva vigente; al aprobarse el pago pasa a confirmada; si el pago falla, a liberada. |
| Entrada (5) | Vigente, en reintegro, devuelta, utilizada | Solo una entrada vigente admite una solicitud de devolución. Al aprobarse pasa a en reintegro; con el reintegro exitoso, a devuelta. Pasa a utilizada cuando se valida su QR en el acceso (Entrega 2) y desde ahí no admite cambios. |
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
- **Dado** una emisión que quedó sin respuesta (en un intento anterior o en un pedido anterior), **cuando** el servicio va a repetirla, **entonces** primero consulta las entradas de la compra en Entradas: si ya se emitieron, las asigna sin volver a emitir; si la lista está vacía, emite como siempre (Entrega 2).
- **Dado** un pago aprobado cuyo evento ya cerró Entradas, **cuando** la emisión responde 409 `EVENTO_CERRADO`, **entonces** no se reintenta: la compra queda fallida, sin entradas, con un único reintegro automático por el total (`reason` "Evento cancelado", origen cancelación de evento) y el aviso `EVENTO_CANCELADO` para el comprador, guardados en la misma transacción. Repetir el pago no crea otro reintegro ni otro aviso. Si las entradas ya se habían emitido antes del cierre, Entradas devuelve las mismas y la compra se confirma (Entrega 2).

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
- **Dado** una entrada que ya no está vigente (en reintegro, devuelta o utilizada) o que pertenece a otro cliente, **cuando** solicito la devolución, **entonces** el servicio la rechaza; si no está vigente, con 409 `ENTRADA_NO_VIGENTE` y un mensaje que nombra su estado.
- **Dado** una solicitud pendiente para una entrada, **cuando** solicito otra para la misma entrada, **entonces** el servicio no crea un duplicado.

### D-2 · Aprobar o rechazar una devolución

**Origen:** US-6.2. **Como** administrador, **quiero** aprobar o rechazar las solicitudes de devolución, **para** controlar los reintegros.

- **Dado** una solicitud pendiente, **cuando** un administrador la aprueba, **entonces** la solicitud queda aprobada y el servicio, en este orden, (a) pide al servicio de entradas pasar la entrada a en reintegro, invalidando su QR, (b) pide al servicio de disponibilidad y reservas liberar el lugar (con el `devolucionId`, para no liberarlo dos veces) y (c) pide al servicio de pagos el reintegro del precio unitario de esa entrada.
- **Dado** una solicitud pendiente cuya entrada se usó después de solicitarla (Entradas responde 409 `ENTRADA_UTILIZADA` al pasarla a en reintegro), **cuando** el administrador la aprueba, **entonces** la solicitud queda aprobada con ese código en su último error y no se pide el reintegro a Pagos (Entrega 2).
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
- **Dado** que se procesa la cancelación, **cuando** el servicio pide a Entradas marcar las entradas del evento, **entonces** la cancelación se da por completa solo si Entradas confirma el cierre del evento (`cerrado: true`), aunque haya marcado 0 entradas. Si el campo no viene, viene en `false` o Entradas no responde, la cancelación queda en proceso con el error guardado y se reintenta al retomarla, sin duplicar reintegros (Entrega 2).
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

## Entrega 2: Recomendaciones, modificación de eventos y avisos

### R-1 · Co-compras de un evento

**Origen:** Entrega 2 (Recomendaciones). **Como** servicio de recomendaciones, **quiero** saber qué otros eventos compraron quienes compraron un evento, **para** recomendar eventos parecidos.

- **Dado** clientes con una compra confirmada del evento A que también compraron B y C, **cuando** Recomendaciones consulta `GET /internal/eventos/A/co-compras`, **entonces** recibe B y C con la cantidad de clientes en común, de mayor a menor, sin el evento A.
- **Dado** un cliente con varias compras confirmadas del mismo evento, **cuando** se cuentan las co-compras, **entonces** cuenta una sola vez.
- **Dado** compras pendientes o fallidas, **cuando** se cuentan las co-compras, **entonces** no cuentan para nada.
- **Dado** que no se pide límite, **cuando** se consulta, **entonces** se devuelven como máximo 10 eventos; un límite mayor a 20 se trata como 20.
- **Dado** un evento sin compras confirmadas, **cuando** se consulta, **entonces** responde 200 con la lista vacía, no 404.

### R-2 · Historial de preferencias de un cliente

**Origen:** Entrega 2 (Recomendaciones). **Como** servicio de recomendaciones, **quiero** conocer los tipos de evento y los eventos que compró un cliente, **para** recomendarle eventos de su interés.

- **Dado** un cliente con 3 compras confirmadas de RECITAL y 1 de TEATRO, **cuando** Recomendaciones consulta `GET /internal/usuarios/{usuarioId}/historial-preferencias`, **entonces** recibe `tipos` con RECITAL (3) y TEATRO (1), de mayor a menor.
- **Dado** una compra nueva, **cuando** se inicia, **entonces** guarda el tipo del evento que informa Eventos; si Eventos no lo informa, la compra se crea igual sin tipo.
- **Dado** compras confirmadas anteriores a la Entrega 2 (sin tipo), **cuando** se arma el historial, **entonces** no cuentan en `tipos` pero sus eventos sí figuran en `eventosComprados`.
- **Dado** un cliente con compras confirmadas, **cuando** se consulta, **entonces** `eventosComprados` trae los eventos distintos, del comprado más recientemente al más antiguo, como máximo 20.
- **Dado** un cliente sin compras confirmadas, **cuando** se consulta, **entonces** responde 200 con las dos listas vacías.

### E-3 · Aplicar la modificación de un evento

**Origen:** Entrega 2. **Como** cliente, **quiero** enterarme si cambian la fecha, el horario o el lugar de un evento que compré, **para** organizarme; **como** equipo, **queremos** que las fechas guardadas se mantengan al día.

- **Dado** que Eventos modifica un evento, **cuando** avisa con `POST /internal/eventos-modificados` (`eventoId`, `version` y al menos uno de `fechaHora`, `lugar` o `ciudad`), **entonces** en una sola transacción se registra la modificación, se actualiza la fecha de inicio guardada en las compras y solicitudes de devolución del evento (si vino `fechaHora`) y se guarda un aviso EVENTO_MODIFICADO por cada cliente con compra confirmada. Responde 202.
- **Dado** una modificación que solo cambia el lugar, **cuando** se aplica, **entonces** no cambia ninguna fecha guardada y los avisos llevan el lugar nuevo.
- **Dado** la misma versión recibida otra vez, o una versión menor que la ya procesada, **cuando** llega, **entonces** no se cambia nada, no se crean avisos y responde 200.
- **Dado** un cliente con varias compras confirmadas del evento, **cuando** se generan los avisos, **entonces** recibe uno solo; los clientes con compras pendientes o fallidas no reciben ninguno.
- **Dado** un cuerpo inválido o un token incorrecto, **cuando** llega el aviso, **entonces** responde 400 o 403 y no guarda nada.
- **Dado** una falla a mitad de la transacción, **cuando** ocurre, **entonces** no queda nada guardado y Eventos puede reintentar con la misma versión.
- Modificar la fecha o el lugar no genera reintegros ni cambia el estado de las entradas.

### N-1 · Avisar a Notificaciones sin afectar la operación

**Origen:** Entrega 2 (Notificaciones). **Como** cliente, **quiero** recibir un correo cuando se confirma mi compra, se resuelve mi devolución, termina mi reintegro o se cancela o modifica mi evento, **para** estar al tanto; **como** equipo, **queremos** que una falla de Notificaciones nunca revierta una compra, una devolución ni un reintegro.

- **Dado** una compra que pasa a confirmada, **cuando** se confirma, **entonces** en la misma transacción se guardan los avisos COMPRA_CONFIRMADA y ENTRADAS_DISPONIBLES.
- **Dado** una devolución aprobada o rechazada por un administrador, o un reintegro que termina exitoso o fallido, **cuando** cambia su estado, **entonces** en la misma transacción se guarda el aviso DEVOLUCION_APROBADA, DEVOLUCION_RECHAZADA o RESULTADO_REINTEGRO.
- **Dado** un evento cancelado, **cuando** llega el aviso de cancelación, **entonces** se guarda un aviso EVENTO_CANCELADO por cada cliente con compra confirmada. Las solicitudes pendientes que la cancelación cierra no generan DEVOLUCION_RECHAZADA.
- **Dado** que la operación de negocio se revierte, **cuando** ocurre, **entonces** no queda ningún aviso guardado. Repetir la operación no crea un segundo aviso (mismo acontecimiento, tipo y destinatario).
- **Dado** que Notificaciones no responde, **cuando** se compra, se devuelve o se reintegra, **entonces** la operación se completa igual y el aviso queda pendiente.

### N-2 · Enviar los avisos con reintentos

**Origen:** Entrega 2 (Notificaciones). **Como** equipo, **queremos** que los avisos pendientes se envíen solos y se reintenten ante fallas, **para** que ningún cliente se quede sin su correo por una caída puntual.

- **Dado** avisos pendientes, **cuando** corre la tarea de envío (cada 10 s), **entonces** cada aviso lo toma una sola ejecución (toma condicional) y, con una respuesta 2xx de Notificaciones, queda enviado.
- **Dado** una falla temporal (sin respuesta, 403 u otro error), **cuando** ocurre, **entonces** el aviso vuelve a pendiente con espera creciente (30 s, 60 s, 120 s...) y el mismo `acontecimientoId`, así Notificaciones no duplica la notificación.
- **Dado** un rechazo definitivo (400, 415 o 422) o los 5 intentos agotados, **cuando** ocurre, **entonces** el aviso queda en error final con el último error.
- **Dado** un aviso que quedó enviándose porque el servicio se cayó, **cuando** pasa su vencimiento (2 minutos), **entonces** vuelve a pendiente contando el intento (o a error final si ya los agotó), y el resultado tardío de ese envío no pisa su estado.
- El envío es de al menos una vez: si se pierde la confirmación, el aviso puede repetirse y Notificaciones lo deduplica.

## Permisos y requisitos técnicos

El API Gateway valida el JWT, descarta los encabezados de identidad del cliente y propaga `X-User-Id` (el `sub` del token) y `X-User-Roles` (puede haber varios roles); este servicio no valida el token, pero sí los permisos de sus propias operaciones. Como confía en esos encabezados, solo debe ser alcanzable a través del Gateway: en la configuración de entrega el puerto 8083 no debe publicarse fuera de la red de Docker (`ticketya-net`).

| ID | Historia | Criterio de aceptación |
| --- | --- | --- |
| P-1 | Como cliente, quiero que solo yo vea mis compras, para proteger mi información. | Un cliente que consulta compras o solicitudes de otro recibe acceso denegado (C-6, D-3). |
| P-2 | Como administrador, quiero ser el único que resuelva devoluciones, para controlar los reintegros. | Un usuario que no es administrador no puede aprobar ni rechazar solicitudes (D-2). |
| P-3 | Como cliente, quiero que solo yo pueda comprar mis reservas, para que nadie pague por mí sin permiso. | Una compra a partir de una reserva ajena se rechaza (C-1). |
| P-4 | Como equipo, queremos que este servicio tenga su propia base relacional, para mantener los servicios desacoplados. | Guarda compras, detalles, claves de idempotencia, solicitudes y reintegros; no accede a tablas de otros servicios; se relaciona con ellos por ID y por APIs REST. |
| P-5 | Como equipo, queremos que las llamadas a otros servicios sean comunicación directa entre servicios, para no exponerlas en el gateway. | Las operaciones internas (confirmar venta, liberar, emitir, cobrar, reintegrar) no pasan por el API Gateway. |
| P-6 | Como equipo, queremos pruebas unitarias de la lógica de compra y devolución, para detectar errores temprano. | Hay pruebas de cada escenario de C-1 a E-2 que corren con los otros servicios reemplazados por dobles de prueba. |
| P-7 | Como equipo, queremos documentar los endpoints con OpenAPI, para que sean claros y probables. | Cada endpoint tiene método, ruta, parámetros, códigos de respuesta, ejemplos JSON y códigos de error. |
| P-9 | Como administrador con varios roles, quiero que se me reconozca como administrador, para operar aunque también sea cliente. | Es administrador quien tiene el rol configurado (`ADMIN`) en `X-User-Roles`, entre otros; sin él, 403 `SOLO_ADMINISTRADOR`. La titularidad sigue por `X-User-Id`, sin importar los roles. Sin `X-User-Roles` se toma `X-User-Role` como único rol; si llegan los dos, manda `X-User-Roles`. |
| P-8 | Como equipo, queremos que solo otros servicios autorizados llamen a los endpoints internos, para que no se expongan datos de compras. | Los `/internal/**` de la Entrega 2 exigen `X-Service-Token`, comparado en tiempo constante; sin él, con uno incorrecto o sin token configurado, responden 403 `SERVICIO_NO_AUTORIZADO`. |

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
| `POST /internal/eventos-modificados` | Eventos (interno, `X-Service-Token`) | Modificación de fecha, horario o lugar. Idempotente por `eventoId` + `version` | 202, 200, 400, 403 |
| `GET /internal/eventos/{eventoId}/co-compras` | Recomendaciones (interno, `X-Service-Token`) | Otros eventos comprados por quienes compraron este. `?limite=` (10 por defecto, tope 20) | 200, 400, 403 |
| `GET /internal/usuarios/{usuarioId}/historial-preferencias` | Recomendaciones (interno, `X-Service-Token`) | Tipos de evento y eventos comprados por el cliente | 200, 403 |

El administrador es quien tiene `ADMIN` entre sus roles de `X-User-Roles` (confirmado por el grupo; el valor es configurable). `X-User-Role` (un solo rol) se acepta como respaldo durante la transición.

## Contratos con los otros servicios

### Eventos (1) — confirmado

- `GET /internal/eventos/{eventoId}`: `eventoId`, `nombre`, `fechaInicio` (ISO-8601, UTC), `estado` (en minúsculas: `borrador`, `publicado`, `agotado`, `cancelado` o `finalizado`) y `sectores` (`sectorId`, `nombre`, `precio`). 404 si no existe. Llamada directa, sin Gateway.
- `POST /eventos-cancelados` (Eventos llama a este servicio): `eventoId`, `motivo`, `canceladoEn`. 202 si lo acepta, 200 si ya lo procesó.
- **Entrega 2, contrato a acordar:** que `GET /internal/eventos/{eventoId}` devuelva `tipo`, y que Eventos llame a `POST /internal/eventos-modificados` con `X-Service-Token`, `eventoId`, un contador `version` por evento y `fechaHora` en UTC (más `lugar` y `ciudad` si cambian).

### Preferencias y recomendaciones (8) — Entrega 2

- Consume `GET /internal/eventos/{eventoId}/co-compras` (`{eventoId, coCompras: [{eventoId, cantidad}]}`) y `GET /internal/usuarios/{usuarioId}/historial-preferencias` (`{usuarioId, tipos: [{tipo, cantidad}], eventosComprados: [...]}`), con `X-Service-Token`. A acordar: si necesita paginación o un límite distinto en co-compras.

### Notificaciones (9) — acordado e implementado

- `POST /internal/notificaciones` con `X-Service-Token`. Cuerpo: `acontecimientoId`, `tipo`, `versionEsquema` (1), `ocurridoEn`, `recursoId`, `usuarioId`, `correlacionId` y `datos` (campos según el tipo). Responde 202 con la notificación nueva o la ya registrada (deduplica por acontecimiento, tipo y destinatario). El contrato completo está en su `openapi.yaml`.

### Reservas (2) — confirmado

El cliente crea la reserva en Reservas (por el Gateway, con un `solicitudId` para no duplicarla); este servicio solo recibe el `reservaId`.

- `GET /disponibilidad/reservas/{reservaId}`: `reservaId`, `clienteId`, `eventoId`, `estado` (VIGENTE, CONFIRMADA, LIBERADA), `expiraEn` (UTC) y `detalles` (`sectorId`, `fila` y `numero` enteros, o `cantidad`).
- `POST /disponibilidad/reservas/{reservaId}/confirmacion`: 204 (repetible), 404, 409 con `RESERVA_VENCIDA` o `RESERVA_LIBERADA`.
- `POST /disponibilidad/reservas/{reservaId}/liberacion`: con `PAGO_RECHAZADO` libera la reserva completa; con `DEVOLUCION_APROBADA` lleva `devolucionId` y los `detalles` devueltos (puede ser parcial). 204 (repetible), 400, 404, 409.

### Pagos (4) — contrato acordado, servicio aún sin implementar

Servicio interno, sin encabezados de usuario, con rutas internas (`/internal/pagos/...` y `/internal/reintegros/...`). Las rutas que usa este servicio se configuran con `servicios.pagos.rutas.*`. Los campos van en inglés y los estados en español.

**Cobro**

- `POST /internal/pagos` con `Idempotency-Key` obligatoria y distinta del `purchaseId`. Body: `{"purchaseId":"<uuid>","amount":50000.00,"paymentMethod":"CREDIT_CARD"}`.
- Respuesta: `paymentId`, `purchaseId`, `amount`, `status` (`APROBADO` o `RECHAZADO`), `paymentMethod`. 201 si es nuevo, 200 si es repetición.
- Un rechazo es una respuesta normal (201 con `RECHAZADO`). El 409 queda para conflictos: misma clave con datos distintos, o clave nueva sobre una compra ya pagada.
- Tras un rechazo, un nuevo intento necesita una clave nueva.
- `GET /internal/pagos/{paymentId}`: 404 si no existe. Este servicio no lo usa.
- `GET /internal/pagos/compra/{purchaseId}`: 200 con la lista de pagos de la compra (vacía `[]` si no tiene ninguno; no responde 404). Este servicio solo tiene en cuenta el pago aprobado.
- Medios: `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`. Sin moneda (pesos), monto positivo con hasta 2 decimales.
- Para pruebas, `DIGITAL_WALLET` da siempre `RECHAZADO`.

**Reintegro**

- `POST /internal/pagos/{paymentId}/reintegros` con `Idempotency-Key` obligatoria. Body: `{"refundRequestId":"<uuid>","amount":10000.00,"reason":"..."}`. `reason` es obligatorio: 400 si falta o va vacío.
- Respuesta: `id`, `paymentId`, `refundRequestId`, `amount`, `status` (`EXITOSO` o `FALLIDO`), `reason`, `attemptCount` y fechas. 201 si es nuevo, 200 si es repetición.
- Solo sobre pagos aprobados (si no, 409). Acepta varios parciales mientras la suma no supere lo cobrado; los fallidos no descuentan saldo. El mismo `refundRequestId` con otra clave da 409.
- Reintento: `POST /internal/reintegros/{refundId}/reintentar`, sin cuerpo ni clave; solo si está fallido (si no, 409). Consulta: `GET /internal/reintegros/{refundId}` (404 si no existe).

### Entradas (5) — confirmado

- `POST /internal/entradas/emision`: idempotente por `idCompra`. Pedido: `idCompra`, `idCliente`, `evento` (`id`, `nombre`, `comienzaEn`) y `lugares`, una línea por lugar con `idSector`, `nombreSector`, `fila` y `numero` (enteros). En un sector no numerado va una línea por lugar con `fila` y `numero` en `null`, y la respuesta los conserva. 201 emisión nueva, 200 reintento, 409 `IDEMPOTENCIA_CONFLICTO`. Devuelve por entrada `idEntrada`, `idSector`, `fila`, `numero` y `codigoQr`. Una fila que no es un número se trata como error de contrato.
- `GET /internal/entradas/{id}`: `idEntrada`, `codigoQr`, `estado`, `evento` (`id`, `nombre`, `comienzaEn`), `sector` anidado (`id`, `nombre`), `fila`, `numero`, `idCompra` e `idCliente`. El sector se toma de `sector.id` y `sector.nombre`. Si no existe: 404 `ENTRADA_NO_ENCONTRADA`; un 404 sin ese código se trata como falla del servicio.
- `POST /internal/entradas/{id}/reintegro` y `POST /internal/entradas/{id}/devolucion`: 200, también al repetirse. Una transición inválida responde 409 `TRANSICION_INVALIDA`, que este servicio registra como conflicto de estado de la entrada, distinto de una caída de Entradas: el paso queda pendiente y un administrador lo retoma.
- Errores: Entradas adopta el formato del grupo, con el código de negocio en `error`. Mientras termina el cambio, este servicio también lo lee de `codigo`.
- `fila` es un entero, como en el resto de los servicios.
- `POST /internal/eventos/{idEvento}/entradas/reintegro`: pasa a en reintegro las entradas vigentes de un evento cancelado, registra el cierre del evento y responde `entradasMarcadas` (sin los IDs) y `cerrado` (Entrega 2). Repetirla es seguro y responde `entradasMarcadas: 0`. `entradasMarcadas` es solo informativo: el monto de cada reintegro se calcula con los datos de este servicio. La cancelación se da por completa solo con `cerrado: true`.
- **Entrega 2:**
  - Todas las llamadas a Entradas llevan `X-Service-Token` (`SERVICIO_TOKEN`) y `X-Correlation-Id`: el del Gateway o, si no hay (tareas programadas), uno generado. Sin token configurado no se envía y el 403 de Entradas se trata como falla técnica.
  - Una entrada pasa a `UTILIZADA` cuando se valida su QR. Pasarla a en reintegro responde 409 `ENTRADA_UTILIZADA`.
  - Con el evento cerrado, `POST /internal/entradas/emision` responde 409 `EVENTO_CERRADO`, salvo que repita una emisión ya hecha, que devuelve las mismas entradas.
  - `GET /internal/compras/{idCompra}/entradas`: las entradas emitidas de una compra, sin emitir. Una lista vacía (nunca 404) significa que no se emitió.

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
| Rol de administrador | `ADMIN`, confirmado por el grupo. Desde la Entrega 2 se decide por pertenencia al conjunto de `X-User-Roles` (un usuario puede tener varios roles). |
| Identidad (Entrega 2) | El Gateway valida el JWT y propaga `X-User-Id` y `X-User-Roles`; este servicio no valida el token. `X-User-Role` se acepta como respaldo si falta `X-User-Roles` y se retira cuando todo el grupo use el Gateway nuevo. Los encabezados de identidad no se registran en los logs. |
| Cancelación de un evento | Aviso idempotente por `eventoId` (202 aceptado o en proceso, 200 ya procesado). Un reintegro por compra confirmada, con `reason` "Evento cancelado" y el precio de las entradas vigentes. Los reintegros fallidos los reintenta un administrador, como en las devoluciones. Una cancelación interrumpida se retoma automáticamente: una revisión periódica (`compras.cancelacion.intervalo-reanudacion`, 60 s; se desactiva con `compras.cancelacion.reanudacion-activa=false`) toma con una actualización condicional las que llevan en proceso más que el plazo. |
| Compra pendiente ya cobrada en un evento cancelado | La cancelación cierra las compras pendientes del evento: pasan a fallidas con un cambio de estado condicional y, si tienen un pago aprobado (registrado o informado por Pagos), reciben un único reintegro por el total. Si un pago se retoma a la vez, gana quien cambia primero el estado. Límite aceptado en la Entrega 1: las entradas emitidas cuya respuesta se perdió quedan en reintegro sin pasar a devueltas. |
| Retención de la clave de idempotencia | No vence en la Entrega 1: se conserva mientras exista la compra. Es seguro porque antes de cobrar se revisa si la compra ya tiene un pago, se consulta a Pagos si otra clave ya intentó pagarla y Pagos rechaza (409) un segundo cobro de una compra ya pagada, así que repetir un pago con una clave olvidada no cobraría dos veces. Una limpieza agregaría código y pruebas sin sumar seguridad; en la Entrega 2 puede sumarse una limpieza programada (por ejemplo, 24 horas después de completarse). |
| Contrato de Entradas | Confirmado por Entradas: pedido de emisión con `lugares` (`idSector`, `nombreSector`, `fila`, `numero`), consulta con el sector anidado, cambios de estado repetibles (200) y 409 `TRANSICION_INVALIDA` como conflicto de estado. Código de error en `error` (o en `codigo` mientras Entradas migra). |
| Estado de un evento | Eventos lo envía en minúsculas: `borrador`, `publicado`, `agotado`, `cancelado` o `finalizado`. Una entrada de un evento `cancelado` no admite devolución (409 `EVENTO_CANCELADO`). |
| Avisos a Notificaciones (Entrega 2) | Outbox: el aviso se guarda en la misma transacción que la operación y una tarea programada lo envía con reintentos. La clave única (acontecimiento, tipo, destinatario) es la misma con la que deduplica Notificaciones. 400, 415 y 422 no se reintentan; 403 sí. Las llamadas a Pagos de un reintegro quedan fuera de la transacción que guarda su resultado y su aviso. |
| Modificación de eventos (Entrega 2) | Idempotente por `eventoId` + `version`; una versión anterior a la ya procesada se ignora. Todo en una transacción. No genera reintegros ni cambia entradas. |
| Endpoints internos (Entrega 2) | `X-Service-Token` con el token compartido `SERVICIO_TOKEN`, provisorio hasta la adaptación a JWT (cambio aparte). |

### Abiertos

1. **Reservas:** la forma de un lugar de sector no numerado en la liberación por devolución (se asume `{"sectorId","cantidad":1}`).
2. **Eventos:** IDs fijos de evento y sectores para los datos de prueba.
3. **Gateway:** puerto sin definir.
4. **Pagos:** confirmar que con las rutas internas no cambian los cuerpos, los códigos de respuesta ni el formato de error (se asume que no).
5. **Eventos (Entrega 2):** que devuelva `tipo` en el evento y que tenga el contador `version` por evento para avisar modificaciones (si no, se usaría la fecha de modificación como identificador).
6. **Grupo (Entrega 2):** nombre definitivo del encabezado y de la variable del token de servicio (hoy `X-Service-Token` y `SERVICIO_TOKEN`) y URL de Notificaciones en el contenedor (`notificaciones:8090`).
7. **Recomendaciones (Entrega 2):** si necesita paginación o un límite distinto en co-compras.
8. **Grupo (Entrega 2):** la configuración de Docker de entrega, con la red `ticketya-net` y sin publicar el puerto 8083 de este servicio, todavía no está en el repositorio.

## Cómo se organiza en OpenSpec

Tres cambios en la Entrega 1 y dos en la Entrega 2, en este orden. Cada criterio Dado / Cuando / Entonces de este documento se convierte en un escenario WHEN / THEN de la spec.

| Orden | Cambio | Historias | Por qué en este lugar |
| --- | --- | --- | --- |
| 1 | `compras-y-pagos` | C-1 a C-6, P-1, P-3 | Camino principal; fija el modelo de compra y la idempotencia. También deja el modelo de datos de los reintegros. |
| 2 | `devoluciones` | D-1 a D-4, P-2 | Necesita compras confirmadas y define la máquina de estados del reintegro. |
| 3 | `cancelacion-de-eventos` | E-1, E-2 | Reutiliza la coordinación de reintegros del cambio anterior, sin aprobación individual. |
| 4 | `compras-entrega-2` | R-1, R-2, E-3, N-1, N-2, P-8 | Entrega 2: suma consultas, avisos y modificación de eventos sobre los estados ya definidos. |
| 5 | `compras-jwt-roles` | P-9 | Entrega 2: adapta la identidad al JWT del Gateway (varios roles) sin cambiar la titularidad ni los códigos de respuesta. |
