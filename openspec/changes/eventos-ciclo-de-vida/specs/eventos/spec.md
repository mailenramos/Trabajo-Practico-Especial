# Spec Delta

## Purpose

Define la creación, preparación, publicación y administración del ciclo de vida de eventos, coordinando la disponibilidad y las devoluciones con los servicios responsables sin duplicar allí sus datos.

## ADDED Requirements

### Requirement: Crear eventos en borrador
El servicio SHALL permitir que un organizador autenticado cree un evento asociado a sí mismo y a un lugar existente, con nombre, descripción, tipo, participantes y fecha/hora de inicio futura.

#### Scenario: Crear evento válido
- **GIVEN** un organizador autenticado y un lugar existente con sectores
- **WHEN** crea un evento con los datos requeridos y fecha/hora de inicio futura
- **THEN** queda asociado al organizador de `X-User-Id` en estado `borrador` y responde `201 Created`

#### Scenario: Rechazar inicio pasado
- **GIVEN** un organizador autenticado y un lugar existente
- **WHEN** intenta crear el evento con fecha/hora de inicio pasada
- **THEN** no se crea y responde `400 Bad Request`

#### Scenario: Rechazar lugar inexistente
- **GIVEN** un organizador autenticado
- **WHEN** intenta crear el evento con un lugar que no existe localmente
- **THEN** no se crea y responde `404 Not Found`

#### Scenario: Rechazar creación sin identidad o con rol no organizador
- **GIVEN** una solicitud sin `X-User-Id` o `X-User-Role`, o con rol distinto de `organizador`
- **WHEN** solicita crear un evento
- **THEN** responde `401 Unauthorized` si falta identidad o `403 Forbidden` si el rol no es organizador, y no crea el evento

### Requirement: Definir límite de entradas por cliente
El servicio SHALL permitir establecer un límite entero de entradas por cliente mayor o igual a uno al crear el evento o mientras esté en borrador, con valor por defecto cuatro.

#### Scenario: Aplicar valor por defecto al omitir el límite
- **GIVEN** una solicitud válida de creación que omite `limiteEntradasPorCliente`
- **WHEN** el servicio crea el evento
- **THEN** guarda el límite como `4`

#### Scenario: Aplicar valor por defecto ante null explícito al crear
- **GIVEN** una solicitud válida de creación con `"limiteEntradasPorCliente": null`
- **WHEN** el servicio crea el evento
- **THEN** trata el valor como no definido, guarda el límite como `4` y responde `201 Created`

#### Scenario: Rechazar límite cero
- **GIVEN** una solicitud de creación o modificación con `limiteEntradasPorCliente` igual a `0`
- **WHEN** el servicio valida el límite
- **THEN** no crea ni modifica el evento y responde `400 Bad Request`

#### Scenario: Rechazar límite negativo
- **GIVEN** una solicitud de creación o modificación con `limiteEntradasPorCliente` menor que `0`
- **WHEN** el servicio valida el límite
- **THEN** no crea ni modifica el evento y responde `400 Bad Request`

#### Scenario: Rechazar límite decimal al crear
- **GIVEN** una solicitud de creación con `limiteEntradasPorCliente` decimal, por ejemplo `2.5`
- **WHEN** el servicio deserializa la solicitud
- **THEN** no trunca el valor, no crea el evento y responde `400 Bad Request`

#### Scenario: Rechazar límite decimal al modificar
- **GIVEN** una solicitud de modificación con `limiteEntradasPorCliente` decimal, por ejemplo `2.5`
- **WHEN** el servicio deserializa la solicitud
- **THEN** no trunca el valor, no modifica el evento y responde `400 Bad Request`

#### Scenario: Rechazar límite enviado como texto al crear
- **GIVEN** una solicitud de creación con `limiteEntradasPorCliente` como texto JSON, por ejemplo `"2"`
- **WHEN** el servicio deserializa la solicitud
- **THEN** no convierte el texto a entero, no crea el evento y responde `400 Bad Request`

#### Scenario: Rechazar límite enviado como texto al modificar
- **GIVEN** una solicitud de modificación con `limiteEntradasPorCliente` como texto JSON, por ejemplo `"2"`
- **WHEN** el servicio deserializa la solicitud
- **THEN** no convierte el texto a entero, no modifica el evento y responde `400 Bad Request`

#### Scenario: Rechazar límite null explícito al modificar
- **GIVEN** una solicitud de modificación con `"limiteEntradasPorCliente": null`
- **WHEN** el servicio deserializa la solicitud
- **THEN** no modifica el evento y responde `400 Bad Request`

#### Scenario: Rechazar modificación si se omite el límite
- **GIVEN** una solicitud de modificación sin el campo `limiteEntradasPorCliente`
- **WHEN** el servicio valida la solicitud
- **THEN** no modifica el evento y responde `400 Bad Request`

#### Scenario: Modificar límite durante borrador
- **GIVEN** un evento en borrador
- **WHEN** el organizador dueño asigna un límite entero mayor o igual a uno
- **THEN** el límite queda guardado y responde `200 OK`

#### Scenario: Rechazar cambio del límite publicado
- **GIVEN** un evento publicado
- **WHEN** se intenta modificar `limiteEntradasPorCliente`
- **THEN** el límite no cambia y responde `409 Conflict`

#### Scenario: Incluir siempre el límite en la inicialización de Reservas
- **GIVEN** un evento listo para publicar con límite definido por el organizador o con el valor por defecto
- **WHEN** se envía la inicialización de disponibilidad a Reservas
- **THEN** el body incluye `limiteEntradasPorCliente` como entero mayor o igual a `1`; si el organizador no lo definió, incluye `4`

### Requirement: Definir precios por sector en borrador
El servicio SHALL permitir al organizador dueño asignar a cada sector del lugar del evento un precio mayor o igual a cero únicamente mientras el evento esté en `borrador`.

#### Scenario: Asignar precio válido a un sector del lugar
- **GIVEN** un evento en borrador cuyo lugar contiene el sector indicado
- **WHEN** el organizador dueño asigna un precio mayor o igual a cero
- **THEN** guarda el precio y una copia del nombre del sector en el evento y responde `200 OK`

#### Scenario: Rechazar precio negativo
- **GIVEN** un evento en borrador y un sector de su lugar
- **WHEN** el organizador dueño asigna un precio menor que cero
- **THEN** el precio no cambia y responde `400 Bad Request`

#### Scenario: Rechazar sector ajeno al lugar
- **GIVEN** un evento en borrador y un sector que no pertenece al lugar asociado
- **WHEN** el organizador dueño intenta asignarle un precio
- **THEN** no guarda el precio, responde `404 Not Found` con el mensaje `El sector {sectorId} no pertenece al lugar del evento` y usa el formato JSON de error estándar

#### Scenario: Rechazar modificación de precio publicado
- **GIVEN** un evento publicado
- **WHEN** se intenta modificar uno de sus precios
- **THEN** el precio permanece inmutable y responde `409 Conflict`

#### Scenario: Rechazar modificación por otro organizador
- **GIVEN** un evento en borrador perteneciente a otro organizador
- **WHEN** un organizador intenta modificar sus precios
- **THEN** responde `403 Forbidden` y no modifica el evento

#### Scenario: Rechazar modificación de precio sin identidad
- **GIVEN** una solicitud para cambiar un precio sin `X-User-Id` o `X-User-Role`
- **WHEN** el servicio procesa la solicitud
- **THEN** responde `401 Unauthorized` y no modifica el evento

### Requirement: Publicar eventos con disponibilidad inicializada
El servicio SHALL publicar un evento solo cuando todos los sectores tengan precio y Reservas confirme la inicialización de disponibilidad.

#### Scenario: Reservas confirma inicialización
- **GIVEN** un evento en borrador con precio para cada sector y cuyo organizador autenticado es el dueño
- **WHEN** solicita publicarlo y Reservas responde `204 No Content` al PUT de inicialización
- **THEN** el evento pasa a `publicado`, se calcula `precioMinimo` y responde `200 OK`

#### Scenario: Rechazar publicación con sectores sin precio
- **GIVEN** un evento en borrador con uno o más sectores sin precio
- **WHEN** el organizador dueño solicita publicarlo
- **THEN** permanece en borrador, se indican los sectores faltantes y responde `400 Bad Request`

#### Scenario: Reservas ya tiene datos distintos
- **GIVEN** un evento en borrador listo para publicar
- **WHEN** Reservas responde `409 Conflict` al PUT de inicialización
- **THEN** permanece en borrador y el organizador recibe `503 Service Unavailable` con el error estándar

#### Scenario: Reservas está caído o devuelve error de servidor
- **GIVEN** un evento en borrador listo para publicar
- **WHEN** Reservas no responde dentro del timeout o responde 5xx
- **THEN** permanece en borrador y el organizador recibe `503 Service Unavailable` con el error estándar, pudiendo reintentar

#### Scenario: Reintentar publicación sin duplicar disponibilidad
- **GIVEN** Reservas ya inicializó el mismo evento con los mismos datos, pero Eventos no recibió la respuesta anterior
- **WHEN** el organizador dueño reintenta la publicación y Reservas responde `204 No Content`
- **THEN** la inicialización no se duplica, el evento pasa a `publicado`, se calcula `precioMinimo` y responde `200 OK`

#### Scenario: Rechazar publicación de evento ajeno o sin identidad
- **GIVEN** un evento en borrador y una solicitud de otro organizador o sin identidad
- **WHEN** intenta publicarlo
- **THEN** responde `403 Forbidden` si es otro organizador o `401 Unauthorized` si faltan encabezados, y el evento permanece en borrador

### Requirement: Administrar suspensión, reactivación y cancelación
El servicio SHALL permitir solo a un administrador suspender o reactivar ventas y cancelar eventos elegibles, sin mezclar la suspensión con el estado formal.

#### Scenario: Suspender venta tras confirmación de Reservas
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador suspende la venta y Reservas confirma con `204`
- **THEN** `ventaSuspendida` queda en `true`, el estado formal no cambia y responde `200 OK`

#### Scenario: Reactivar venta tras confirmación de Reservas
- **GIVEN** un evento publicado o agotado con `ventaSuspendida` en `true`
- **WHEN** un administrador reactiva la venta y Reservas confirma con `204`
- **THEN** `ventaSuspendida` queda en `false`, el estado formal no cambia y responde `200 OK`

#### Scenario: No suspender ni reactivar si Reservas falla
- **GIVEN** un evento cuyo estado local permite suspender o reactivar
- **WHEN** Reservas devuelve error o no responde antes del cambio local
- **THEN** el estado y `ventaSuspendida` no cambian y se responde `503 Service Unavailable`

#### Scenario: Cancelar tras confirmación de Reservas y avisar a Compras
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador lo cancela y Reservas confirma con `204`
- **THEN** pasa a `cancelado`, se registra el aviso a Compras con motivo y fecha, y responde `200 OK`

#### Scenario: No cancelar si Reservas falla
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** Reservas falla o no responde antes del cambio local
- **THEN** el evento conserva su estado actual, no se registra aviso de cancelación a Compras y se responde `503 Service Unavailable`

#### Scenario: Compras no responde al aviso de cancelación
- **GIVEN** un evento cancelado luego de la confirmación de Reservas
- **WHEN** Compras no responde al aviso
- **THEN** el evento sigue cancelado y el aviso queda pendiente para reenvío

#### Scenario: Rechazar cancelación de evento finalizado o cancelado
- **GIVEN** un evento `finalizado` o `cancelado`
- **WHEN** un administrador intenta cancelarlo
- **THEN** no cambia el evento ni duplica el aviso y responde `409 Conflict`

#### Scenario: Rechazar acción administrativa a otro rol o sin identidad
- **GIVEN** una solicitud sin identidad o con rol distinto de `administrador`
- **WHEN** intenta suspender, reactivar o cancelar un evento
- **THEN** responde `401 Unauthorized` si falta `X-User-Id` o `X-User-Role`, o `403 Forbidden` si el rol no es administrador, sin modificar el evento

### Requirement: Reflejar automáticamente disponibilidad y finalización
El servicio SHALL procesar de forma idempotente los avisos de disponibilidad y finalizar eventos al alcanzar su fecha/hora de inicio, coordinando con Reservas antes de cambiar el estado local.

#### Scenario: Recibir aviso de agotado
- **GIVEN** un evento `publicado`
- **WHEN** Reservas envía `PUT /eventos/{eventoId}/disponibilidad` con `agotado: true`
- **THEN** pasa a `agotado` y responde `204 No Content`

#### Scenario: Recibir recuperación de disponibilidad
- **GIVEN** un evento `agotado`
- **WHEN** Reservas envía el mismo endpoint con `agotado: false`
- **THEN** pasa a `publicado` y responde `204 No Content`

#### Scenario: Aviso duplicado de disponibilidad
- **GIVEN** un evento que ya refleja el valor de `agotado` recibido
- **WHEN** Reservas repite el aviso
- **THEN** no cambia el evento y responde `204 No Content`

#### Scenario: Aviso tardío para evento cancelado o finalizado
- **GIVEN** un evento `cancelado` o `finalizado`
- **WHEN** Reservas envía un aviso de agotado o recuperación
- **THEN** no cambia el evento y responde `204 No Content`

#### Scenario: Aviso de disponibilidad para evento inexistente o en borrador
- **GIVEN** un identificador de evento inexistente o un evento `borrador`
- **WHEN** Reservas envía un aviso de disponibilidad
- **THEN** responde `404 Not Found` para evento inexistente o `409 Conflict` para borrador

#### Scenario: Callback sin encabezados de usuario ni Gateway
- **GIVEN** Reservas llama directamente al endpoint interno sin `X-User-Id` ni `X-User-Role`
- **WHEN** envía un aviso válido de disponibilidad
- **THEN** el servicio procesa el callback según el estado del evento y responde `204 No Content` cuando corresponde

#### Scenario: Finalizar automáticamente un evento vencido
- **GIVEN** un evento que debe finalizar y cuya fecha/hora de inicio ya pasó
- **WHEN** el proceso automático solicita a Reservas cambiar la venta a `FINALIZADA` y Reservas confirma con `204`
- **THEN** el evento pasa a `finalizado`

#### Scenario: Reservas falla al finalizar automáticamente
- **GIVEN** un evento pendiente de finalización
- **WHEN** Reservas falla o no responde antes del cambio local
- **THEN** el estado no cambia y el proceso puede reintentar

### Requirement: Ofrecer a Compras una consulta interna del evento
El servicio SHALL ofrecer a Compras una consulta interna independiente del detalle público, sin headers de usuario y no enrutable por el API Gateway.

#### Scenario: Compras consulta evento existente
- **GIVEN** un evento existente en cualquier estado
- **WHEN** Compras consulta `GET /internal/eventos/{eventoId}`
- **THEN** responde `200 OK` con `eventoId`, `nombre`, `fechaInicio` en ISO-8601 UTC, `estado` y sectores con `sectorId`, nombre y precio decimal

#### Scenario: Compras consulta evento inexistente
- **GIVEN** un identificador de evento que no existe
- **WHEN** Compras consulta la ruta interna
- **THEN** responde `404 Not Found`

### Requirement: Mantener estados y formato de errores acordados
El estado formal SHALL ser únicamente `borrador`, `publicado`, `agotado`, `cancelado` o `finalizado`; los errores controlados SHALL usar el formato JSON común acordado.

#### Scenario: Suspensión no crea un estado formal
- **GIVEN** un evento publicado o agotado
- **WHEN** se suspende o reactiva la venta
- **THEN** cambia solo `ventaSuspendida` y el estado formal conserva su valor

#### Scenario: Error de validación usa formato común
- **GIVEN** una solicitud pública con un campo inválido
- **WHEN** el servicio responde con un error HTTP
- **THEN** el JSON contiene `timestamp`, `status`, `error`, `mensaje` y `campos` como lista de `{campo, mensaje}`; omite `campos` si no aplica

#### Scenario: Compartir identidad de asiento con Reservas
- **GIVEN** un sector numerado de un evento publicado
- **WHEN** Eventos y Reservas refieren a un asiento
- **THEN** ambos lo identifican mediante `(sectorId, fila, numero)`, dentro de los rangos configurados
