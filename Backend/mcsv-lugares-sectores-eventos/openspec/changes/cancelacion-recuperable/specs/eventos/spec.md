## MODIFIED Requirements

### Requirement: Administrar suspensión, reactivación y cancelación
El servicio SHALL permitir solo a un usuario autenticado cuya lista `X-User-Roles` incluya `ADMIN` suspender o reactivar ventas y cancelar eventos elegibles, sin mezclar la suspensión con el estado formal. Cuando una operación local origine un aviso a otro servicio, SHALL persistir el cambio de evento y el aviso outbox en la misma transacción MongoDB. El fallo posterior de entrega SHALL no revertir el cambio ni afectar la respuesta exitosa al usuario. La intención de cancelar SHALL persistirse antes de llamar a Reservas.

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
- **WHEN** Reservas no responde o devuelve un error distinto de `409` antes del cambio local
- **THEN** el estado y `ventaSuspendida` no cambian y se responde `503 Service Unavailable`

#### Scenario: Conflicto de Reservas al suspender o reactivar
- **GIVEN** un evento cuyo estado local permite suspender o reactivar
- **WHEN** Reservas responde `409` al cambiar el estado de venta
- **THEN** MS1 responde `409 Conflict` sin modificar el evento

#### Scenario: Cancelar tras confirmación de Reservas y notificar a Compras
- **GIVEN** un evento `publicado` o `agotado` con intención de cancelación persistida
- **WHEN** Reservas confirma con `204` y se confirma la transacción local
- **THEN** el evento pasa a `cancelado`, se registran los avisos a Compras y MS7 en la misma transacción y el administrador recibe `200 OK`; se intenta entregar los avisos sin revertir la cancelación si fallan

#### Scenario: Cancelar con motivo omitido
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador autenticado con `X-User-Roles` que incluye `ADMIN` cancela sin body o sin el campo `motivo` y Reservas confirma con `204`
- **THEN** se usa un motivo por defecto, se registra el instante UTC de cancelación y el evento queda `cancelado` con respuesta `200 OK`

#### Scenario: Compras acepta el aviso de cancelación
- **GIVEN** Reservas confirmó la cancelación y el evento y el aviso outbox quedaron confirmados localmente
- **WHEN** Compras responde con cualquier `2xx` al aviso
- **THEN** el aviso pasa a `ENTREGADO` porque Compras aceptó la recepción, sin asumir que terminó su procesamiento o los reintegros, y el administrador recibe `200 OK`

#### Scenario: Compras ya procesó el aviso de cancelación
- **GIVEN** Reservas confirmó la cancelación y el aviso outbox quedó confirmado localmente
- **WHEN** Compras responde `200 OK` porque ya recibió o procesó la cancelación
- **THEN** el aviso pasa a `ENTREGADO` y no vuelve a entregarse automáticamente

#### Scenario: No cancelar si Reservas falla
- **GIVEN** un evento `publicado` o `agotado` con intención de cancelar persistida
- **WHEN** Reservas falla o no responde durante el intento inmediato
- **THEN** el evento conserva su estado formal, no se crean aún avisos a Compras ni MS7, responde `503 Service Unavailable` y la intención queda sujeta a la política de recuperación de outbox

#### Scenario: Compras no responde al aviso de cancelación
- **GIVEN** un evento cancelable y Reservas confirmó la cancelación
- **WHEN** la transacción local confirma el evento cancelado y los avisos outbox, pero Compras falla o no responde al intento inmediato
- **THEN** la cancelación sigue hecha, el administrador recibe `200 OK` y el aviso permanece `PENDIENTE` para reintento

#### Scenario: Reenviar aviso de cancelación pendiente
- **GIVEN** un aviso de cancelación `PENDIENTE` cuyo próximo intento ya venció
- **WHEN** el dispatcher general lo entrega y Compras responde con cualquier `2xx`
- **THEN** el aviso pasa a `ENTREGADO` y se conserva el identificador original

#### Scenario: Rechazar cancelación de evento finalizado o cancelado
- **GIVEN** un evento `finalizado` o `cancelado`
- **WHEN** un administrador intenta cancelarlo
- **THEN** no cambia el evento ni duplica los avisos y responde `409 Conflict`

#### Scenario: Rechazar acción administrativa a otro rol o sin identidad
- **GIVEN** una solicitud sin identidad o cuya lista `X-User-Roles` no incluye `ADMIN`
- **WHEN** intenta suspender, reactivar o cancelar un evento
- **THEN** responde `401 Unauthorized` si falta `X-User-Id` o `X-User-Roles`, o `403 Forbidden` si la lista no incluye el rol administrador, sin modificar el evento

### Requirement: Persistir avisos outbox atómicamente
Cuando una mutación origine avisos, MS1 SHALL guardar el cambio local y los avisos en la misma transacción MongoDB. Cada aviso SHALL incluir ID estable, tipo, `eventoId`, destino, estado, intentos, próximo intento, último error y fechas, y SHALL admitir método HTTP y payload opcionales. Los estados SHALL ser `PENDIENTE`, `PROCESANDO`, `ENTREGADO` o `ERROR_FINAL`. El método ausente SHALL conservar el comportamiento `POST` de avisos existentes.

#### Scenario: Mantener el evento y el aviso atómicos
- **GIVEN** Reservas confirmó la cancelación y una transacción escribe el cambio del evento, el resultado del trabajo y los avisos a Compras y MS7
- **WHEN** falla una de las escrituras antes del commit
- **THEN** no queda persistido el cambio de cancelación ni un aviso huérfano; el trabajo continúa recuperable

#### Scenario: Mantener la publicación y su aviso atómicos
- **GIVEN** una publicación tras la confirmación de Reservas
- **WHEN** falla una de las escrituras del evento o del aviso outbox antes del commit
- **THEN** no queda persistido ni el cambio local del evento ni un aviso huérfano

### Requirement: Entregar avisos con reintentos limitados
El dispatcher SHALL enviar avisos fuera de transacciones usando el método indicado, o `POST` si falta, y body solo cuando corresponda. Para notificaciones, cualquier `2xx` marca `ENTREGADO`; el trabajo de Reservas requiere `204`. Reintenta timeouts, errores de conexión, `5xx`, `408` y `429`; el resto de `4xx` y las respuestas `3xx` son definitivos. El máximo es 8 reclamos con backoff de 30 s × 2, tope 15 min y sin jitter; el polling es configurable y el lease fijo de 60 s.

#### Scenario: Entregar al destino fuera de la transacción
- **GIVEN** un cambio de evento y su aviso fueron confirmados por MongoDB
- **WHEN** el dispatcher envía el aviso por HTTP
- **THEN** la llamada remota usa timeouts de conexión y lectura de `2 s` y `5 s` y no forma parte de la transacción MongoDB

#### Scenario: Reservas no confirma con 204
- **GIVEN** el trabajo de cierre de venta de Reservas no agotó sus intentos
- **WHEN** Reservas responde con un `2xx` distinto de `204`
- **THEN** el cierre no se considera confirmado y el trabajo queda pendiente para reintento

#### Scenario: Sin token no se procesan avisos
- **GIVEN** el perfil `docker` no está activo, no hay token configurado y existe un aviso `PENDIENTE`
- **WHEN** el scheduler o la entrega inmediata intenta procesarlo
- **THEN** no hay barrido, reclamo ni HTTP y el aviso permanece `PENDIENTE`, sin consumir intentos ni modificar `ultimoError`; una vez configurado el token, se entrega en el siguiente intento elegible

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
- **GIVEN** un token configurado y un aviso `PENDIENTE` con 8 intentos o `PROCESANDO` con el lease vencido y 8 intentos
- **WHEN** comienza una pasada del scheduler
- **THEN** el aviso pasa a `ERROR_FINAL` con último error `intentos agotados`, se limpia su lease si lo tenía y no se entrega en esa pasada ni vuelve a reclamarse automáticamente

#### Scenario: Agotar los intentos durante una entrega fallida
- **GIVEN** un aviso que alcanza el límite de 8 intentos o recibe un error definitivo
- **WHEN** el dispatcher registra el resultado
- **THEN** pasa a `ERROR_FINAL`, conserva el último error para revisión y no vuelve a seleccionarlo automáticamente

### Requirement: Autenticar las entregas salientes de outbox
MS1 SHALL enviar `X-Service-Token` con el valor configurado de `servicio.token` en todas las llamadas salientes de outbox, además de conservar `X-Aviso-Id`. El token SHALL provenir de `SERVICIO_TOKEN` sin valor predeterminado secreto. MS1 SHALL NOT registrar ni persistir el valor del token.

#### Scenario: Enviar el token junto al identificador estable
- **GIVEN** `SERVICIO_TOKEN` contiene un valor no vacío y hay un aviso listo para entregar
- **WHEN** la outbox envía o reenvía el aviso por HTTP
- **THEN** incluye el valor configurado en `X-Service-Token` y el mismo ID estable en `X-Aviso-Id`, sin alterar el destino ni el payload

#### Scenario: Usar el token para todos los tipos de aviso
- **GIVEN** hay avisos de publicación y cancelación listos para entregar y un token configurado
- **WHEN** el cliente común de outbox los envía
- **THEN** cada solicitud incluye `X-Service-Token` independientemente del tipo de aviso o método HTTP

#### Scenario: No revelar el token
- **GIVEN** hay un token configurado y un aviso se entrega o falla
- **WHEN** MS1 registra el resultado
- **THEN** el valor del token no aparece en logs ni en `ultimoError` ni se persiste en el aviso

## ADDED Requirements

### Requirement: Recuperar la intención de cancelar
MS1 SHALL persistir la intención y el trabajo de Reservas atómicamente antes de cualquier llamada remota. SHALL procesarlos mediante la outbox existente, sin estados públicos nuevos, y SHALL confirmar cancelación, resultado y avisos en una transacción tras el `204` de Reservas. Un reclamo vencido SHALL NOT confirmar ni sobrescribir un reclamo posterior.

#### Scenario: Recuperar después de un corte
- **GIVEN** la intención y el trabajo de Reservas se confirmaron y MS1 se interrumpe antes de llamar a Reservas
- **WHEN** el scheduler recupera el trabajo y Reservas confirma `204`
- **THEN** MS1 cancela el evento, crea una sola vez ambos avisos y los entrega según su política

#### Scenario: Recuperar una respuesta perdida
- **GIVEN** Reservas cerró la venta pero MS1 no recibió la respuesta o se cortó antes del commit local
- **WHEN** vence el lease y el scheduler repite el cierre idempotente y recibe `204`
- **THEN** confirma la cancelación y los avisos sin duplicarlos

#### Scenario: No duplicar intención durante reintentos
- **GIVEN** una cancelación ya está pendiente o el evento ya quedó cancelado
- **WHEN** un administrador repite la solicitud con el mismo u otro motivo
- **THEN** no modifica el motivo original ni crea avisos adicionales; mientras no termine responde `503` y una vez cancelado responde `409`

#### Scenario: Proteger una cancelación de operaciones concurrentes
- **GIVEN** un evento con una intención de cancelación pendiente
- **WHEN** se intenta suspender, reactivar, cancelar nuevamente o finalizarlo mediante el scheduler
- **THEN** suspender y reactivar responden `409`, cancelar responde `503` y el scheduler omite el evento hasta la próxima pasada; las escrituras locales concurrentes usan control de versión

#### Scenario: Invalidar caché tras cancelar
- **GIVEN** Reservas confirmó el cierre de venta
- **WHEN** se confirma la transacción de cancelación local
- **THEN** MS1 usa el invalidador central después del commit; las otras instancias o un corte antes de invalidar pueden conservar datos hasta el TTL

#### Scenario: Agotar la recuperación
- **GIVEN** el trabajo de Reservas alcanza el límite de intentos o recibe un error definitivo
- **WHEN** el dispatcher registra el resultado o el scheduler barre un lease agotado
- **THEN** queda `ERROR_FINAL` para revisión y no se reintenta automáticamente ni se revierte ninguna confirmación remota

### Requirement: Notificar cancelación a Fila virtual
MS1 SHALL crear el aviso `cancelacion-fila-{eventoId}` al cancelar, incluso si `requiereFila=false`, y enviarlo por `PUT {FILAS_BASE_URL}/internal/filas/eventos/{eventoId}/cancelacion` sin body. SHALL usar los headers comunes de outbox. `FILAS_BASE_URL` SHALL usar `http://localhost:8088` en host y `http://fila-virtual:8088` en Compose, configurables por entorno.

#### Scenario: Entregar aviso de cancelación a MS7
- **GIVEN** un evento quedó cancelado y su aviso a MS7 está pendiente
- **WHEN** MS7 responde `204` o cualquier otro `2xx`
- **THEN** el aviso pasa a `ENTREGADO`, conservando el ID estable y sin revertir el evento

#### Scenario: Reintentar una falla transitoria de MS7
- **GIVEN** un aviso a MS7 no agotó sus intentos
- **WHEN** MS7 responde `5xx` o vence el timeout
- **THEN** el aviso queda pendiente para reintento y el evento permanece cancelado

#### Scenario: Marcar error definitivo de MS7
- **GIVEN** un aviso a MS7 pendiente
- **WHEN** MS7 responde `400`
- **THEN** el aviso queda `ERROR_FINAL` sin reintento automático y el evento permanece cancelado

#### Scenario: Notificar un evento sin fila
- **GIVEN** un evento con `requiereFila=false` queda cancelado
- **WHEN** se confirma la transacción de cancelación
- **THEN** se crea igualmente el aviso a MS7, que puede responder `204` aunque no haya fila

#### Scenario: Mantener avisos anteriores
- **GIVEN** un aviso de publicación o cancelación a Compras creado sin el nuevo campo de método
- **WHEN** el dispatcher lo procesa
- **THEN** envía `POST` con el payload y headers anteriores sin modificar su contrato
