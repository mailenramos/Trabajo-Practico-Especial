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
- **WHEN** Compras responde con cualquier `2xx` al aviso
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
- **WHEN** el dispatcher general lo entrega y Compras responde con cualquier `2xx`
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

### Requirement: Entregar avisos con reintentos limitados
El dispatcher SHALL enviar avisos mediante `POST` fuera de la transacción con timeouts de conexión/lectura de `2 s`/`5 s`; cualquier `2xx` marca `ENTREGADO`. Reintenta timeouts, errores de conexión, `5xx`, `408` y `429`; el resto de `4xx` y las respuestas `3xx` son definitivos. El máximo es 8 reclamos con backoff de 30 s × 2, tope 15 min y sin jitter; el polling es configurable y el lease fijo de 60 s.

#### Scenario: Entregar al destino fuera de la transacción
- **GIVEN** un cambio de evento y su aviso fueron confirmados por MongoDB
- **WHEN** el dispatcher envía el aviso por HTTP
- **THEN** la llamada remota usa los timeouts establecidos y no forma parte de la transacción MongoDB

#### Scenario: No reintentar un error 4xx definitivo
- **GIVEN** un aviso pendiente y un destino que responde con un `4xx` distinto de `408` o `429`
- **WHEN** el dispatcher registra la respuesta
- **THEN** el aviso pasa a `ERROR_FINAL`, conserva el error y no se reintenta

#### Scenario: No reintentar una respuesta 3xx
- **GIVEN** un aviso pendiente y un destino que responde con una redirección `3xx`
- **WHEN** el dispatcher registra la respuesta
- **THEN** el aviso pasa a `ERROR_FINAL`, conserva el error y no se reintenta

#### Scenario: Reintentar errores temporales de entrega
- **GIVEN** un aviso pendiente y un destino que responde con `5xx`, `408` o `429`, no responde antes del timeout o no está disponible por un error de conexión
- **WHEN** el dispatcher registra el fallo antes de agotar los intentos
- **THEN** el aviso registra intentos y último error, vuelve a quedar pendiente con próximo intento según la espera progresiva y el cambio original del evento permanece confirmado

#### Scenario: Barrer avisos con intentos agotados antes de entregarlos
- **GIVEN** un aviso `PENDIENTE` con 8 intentos o `PROCESANDO` con el lease vencido y 8 intentos
- **WHEN** comienza una pasada del scheduler
- **THEN** el aviso pasa a `ERROR_FINAL` con último error `intentos agotados`, se limpia su lease si lo tenía y no se entrega en esa pasada ni vuelve a reclamarse automáticamente

#### Scenario: Agotar los intentos durante una entrega fallida
- **GIVEN** un aviso que alcanza el límite de 8 intentos o recibe un error definitivo
- **WHEN** el dispatcher registra el resultado
- **THEN** pasa a `ERROR_FINAL`, conserva el último error para revisión y no vuelve a seleccionarlo automáticamente

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
