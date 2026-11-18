## MODIFIED Requirements

### Requirement: Administrar suspensión, reactivación y cancelación
El servicio SHALL permitir solo a un usuario autenticado cuya lista `X-User-Roles` incluya `ADMIN` suspender o reactivar ventas y cancelar eventos elegibles, sin mezclar la suspensión con el estado formal. Cuando una operación local origine un aviso a otro servicio, SHALL persistir el cambio de evento y el aviso outbox en la misma transacción MongoDB. El fallo posterior de entrega SHALL no revertir el cambio ni afectar la respuesta exitosa al usuario.

#### Scenario: Suspender venta tras confirmación de Reservas
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador autenticado con `X-User-Roles` que incluye `ADMIN` suspende la venta y Reservas confirma con `204`
- **THEN** `ventaSuspendida` queda en `true`, el estado formal no cambia y responde `200 OK`

#### Scenario: Reactivar venta tras confirmación de Reservas
- **GIVEN** un evento publicado o agotado con `ventaSuspendida` en `true`
- **WHEN** un administrador autenticado con `X-User-Roles` que incluye `ADMIN` reactiva la venta y Reservas confirma con `204`
- **THEN** `ventaSuspendida` queda en `false`, el estado formal no cambia y responde `200 OK`

#### Scenario: No suspender ni reactivar si Reservas falla
- **GIVEN** un evento cuyo estado local permite suspender o reactivar
- **WHEN** Reservas devuelve error o no responde antes del cambio local
- **THEN** el estado y `ventaSuspendida` no cambian y se responde `503 Service Unavailable`

#### Scenario: Cancelar tras confirmación de Reservas y notificar a Compras
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador autenticado con `X-User-Roles` que incluye `ADMIN` lo cancela y Reservas confirma con `204`
- **THEN** el evento pasa a `cancelado`, se registra el aviso outbox en la misma transacción MongoDB que el cambio local, se intenta entregarlo a Compras y el administrador recibe `200 OK`

#### Scenario: Cancelar con motivo omitido
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador autenticado con `X-User-Roles` que incluye `ADMIN` cancela sin body o sin el campo `motivo` y Reservas confirma con `204`
- **THEN** se usa un motivo por defecto, se registra el instante UTC de cancelación y el evento queda `cancelado` con respuesta `200 OK`

#### Scenario: Compras acepta el aviso de cancelación
- **GIVEN** Reservas confirmó la cancelación y el evento y el aviso outbox quedaron confirmados localmente
- **WHEN** Compras responde `202 Accepted` al aviso
- **THEN** el aviso pasa a `ENTREGADO` porque Compras aceptó la recepción, sin asumir que terminó su procesamiento, y el administrador recibe `200 OK`

#### Scenario: Compras ya procesó el aviso de cancelación
- **GIVEN** Reservas confirmó la cancelación y el aviso outbox quedó confirmado localmente
- **WHEN** Compras responde `200 OK` porque ya recibió o procesó la cancelación
- **THEN** el aviso pasa a `ENTREGADO` y no vuelve a entregarse automáticamente

#### Scenario: No cancelar si Reservas falla
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** Reservas falla o no responde antes del cambio local
- **THEN** el evento conserva su estado actual, no se registra aviso outbox a Compras y se responde `503 Service Unavailable`

#### Scenario: Compras no responde al aviso de cancelación
- **GIVEN** un evento cancelable y Reservas confirmó la cancelación
- **WHEN** la transacción local confirma el evento cancelado y el aviso outbox, pero Compras falla o no responde al intento inmediato
- **THEN** la cancelación sigue hecha, el administrador recibe `200 OK` y el aviso permanece `PENDIENTE` para reintento

#### Scenario: Reenviar aviso de cancelación pendiente
- **GIVEN** un aviso de cancelación `PENDIENTE` cuyo próximo intento ya venció
- **WHEN** el dispatcher general lo entrega y Compras responde `202` o `200`
- **THEN** el aviso pasa a `ENTREGADO` y se conserva el identificador original

#### Scenario: Rechazar cancelación de evento finalizado o cancelado
- **GIVEN** un evento `finalizado` o `cancelado`
- **WHEN** un administrador intenta cancelarlo
- **THEN** no cambia el evento ni duplica el aviso y responde `409 Conflict`

#### Scenario: Rechazar acción administrativa a otro rol o sin identidad
- **GIVEN** una solicitud sin identidad o cuya lista `X-User-Roles` no incluye `ADMIN`
- **WHEN** intenta suspender, reactivar o cancelar un evento
- **THEN** responde `401 Unauthorized` si falta `X-User-Id` o `X-User-Roles`, o `403 Forbidden` si la lista no incluye el rol administrador, sin modificar el evento

## ADDED Requirements

### Requirement: Persistir avisos outbox atómicamente
Cuando una mutación origine un aviso, MS1 SHALL guardar el cambio local y un aviso en la colección MongoDB `outbox` en la misma transacción. Cada aviso SHALL incluir ID estable, tipo, `eventoId`, destino, payload, estado, intentos, próximo intento, último error y fechas. Los estados SHALL ser `PENDIENTE`, `PROCESANDO`, `ENTREGADO` o `ERROR_FINAL`. El productor implementado en este cambio SHALL ser la cancelación a Compras.

#### Scenario: Mantener el evento y el aviso atómicos
- **GIVEN** una cancelación que escribe el cambio del evento y el aviso outbox en una transacción
- **WHEN** falla una de las escrituras antes del commit
- **THEN** no queda persistido ni el cambio local del evento ni un aviso huérfano

### Requirement: Entregar avisos con reintentos configurables
El dispatcher programado SHALL enviar avisos HTTP fuera de la transacción, con timeout de conexión de `2 s` y lectura de `5 s`. Timeouts, errores de conexión, `5xx`, `408` y `429` SHALL reintentarse; otros `4xx` SHALL ser definitivos. Se permitirán hasta 8 intentos contados al reclamar, con espera de 30 s duplicada por intento, tope de 15 min y sin jitter. `200`/`202` marcan `ENTREGADO`; errores definitivos o límite agotado marcan `ERROR_FINAL` sin reintento automático.

#### Scenario: Entregar al destino fuera de la transacción
- **GIVEN** un cambio de evento y su aviso fueron confirmados por MongoDB
- **WHEN** el dispatcher envía el aviso por HTTP
- **THEN** la llamada remota usa los timeouts establecidos y no forma parte de la transacción MongoDB

#### Scenario: No reintentar un error 4xx definitivo
- **GIVEN** un aviso pendiente y un destino que responde con un `4xx` distinto de `408` o `429`
- **WHEN** el dispatcher registra la respuesta
- **THEN** el aviso pasa a `ERROR_FINAL`, conserva el error y no se reintenta

#### Scenario: Reintentar un error 5xx o timeout
- **GIVEN** un aviso pendiente y un destino que responde con `5xx` o no responde antes del timeout
- **WHEN** el dispatcher registra el fallo antes de agotar los intentos
- **THEN** el aviso vuelve a quedar pendiente con próximo intento según la espera progresiva

#### Scenario: Agotar los reintentos sin volver a entregar
- **GIVEN** un aviso que alcanza el límite configurado de intentos o recibe un error definitivo
- **WHEN** el dispatcher registra el resultado
- **THEN** pasa a `ERROR_FINAL`, conserva el último error para revisión y no vuelve a seleccionarlo automáticamente

#### Scenario: Reintentar un destino temporalmente caído
- **GIVEN** un aviso pendiente y un destino temporalmente no disponible
- **WHEN** falla la entrega HTTP antes de alcanzar el límite
- **THEN** el aviso registra intentos y último error, queda elegible en el próximo intento programado según la espera progresiva y el cambio original del evento permanece confirmado

### Requirement: Reclamar avisos de forma exclusiva e idempotente
El dispatcher SHALL reclamar avisos atómicamente con un lease de 60 s para impedir entregas simultáneas desde varias instancias. Cada reclamo SHALL contar como intento. Una reentrega SHALL conservar el ID estable original. Un aviso `PROCESANDO` vencido SHALL poder recuperarse respetando el límite de 8 intentos.

#### Scenario: Enviar el identificador estable en X-Aviso-Id
- **GIVEN** un aviso de cancelación con ID `cancelacion-{eventoId}`
- **WHEN** el dispatcher lo entrega o reentrega a Compras
- **THEN** envía el mismo ID en el header `X-Aviso-Id`, sin cambiar el payload; Compras puede ignorar el header y deduplicar por `eventoId`

#### Scenario: Conservar el mismo ID al reentregar
- **GIVEN** un aviso cuya entrega anterior pudo ser recibida por el destino pero no se confirmó en MS1
- **WHEN** el dispatcher vuelve a entregar el aviso
- **THEN** envía el mismo identificador estable para que el destino pueda deduplicar y no crea un aviso nuevo

#### Scenario: No entregar un aviso dos veces en paralelo
- **GIVEN** dos instancias de MS1 intentan seleccionar el mismo aviso elegible
- **WHEN** ambas usan la selección atómica para reclamarlo
- **THEN** solo una obtiene el aviso para procesarlo y la otra no inicia una entrega concurrente de ese aviso

#### Scenario: Recuperar un aviso en PROCESANDO vencido
- **GIVEN** un aviso `PROCESANDO` cuyo plazo de procesamiento venció y que no agotó el límite de intentos
- **WHEN** el dispatcher lo recupera atómicamente
- **THEN** vuelve a intentar la entrega conservando el ID estable y respetando el límite configurado
