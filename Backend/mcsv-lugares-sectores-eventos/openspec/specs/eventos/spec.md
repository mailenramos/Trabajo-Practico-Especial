# eventos Specification

## Purpose
Define la creación, preparación, publicación y administración del ciclo de vida de eventos, coordinando la disponibilidad y las devoluciones con los servicios responsables sin duplicar allí sus datos.

## Requirements

### Requirement: Crear eventos en borrador
El servicio SHALL permitir que un organizador autenticado cree un evento asociado a sí mismo y a un lugar existente, con nombre, descripción, tipo, participantes y fecha/hora de inicio futura.

#### Scenario: Crear evento válido
- **GIVEN** un organizador autenticado con `X-User-Roles` que incluye `ORGANIZADOR` y un lugar existente con sectores
- **WHEN** crea un evento con los datos requeridos y fecha/hora de inicio futura
- **THEN** queda asociado al organizador de `X-User-Id` en estado `borrador` y responde `201 Created`

#### Scenario: Rechazar inicio pasado
- **GIVEN** un organizador autenticado con `X-User-Roles` que incluye `ORGANIZADOR` y un lugar existente
- **WHEN** intenta crear el evento con fecha/hora de inicio pasada
- **THEN** no se crea y responde `400 Bad Request`

#### Scenario: Rechazar lugar inexistente
- **GIVEN** un organizador autenticado con `X-User-Roles` que incluye `ORGANIZADOR`
- **WHEN** intenta crear el evento con un lugar que no existe localmente
- **THEN** no se crea y responde `404 Not Found`

#### Scenario: Rechazar creación sin identidad o con rol no organizador
- **GIVEN** una solicitud sin `X-User-Id` o `X-User-Roles`, o con `X-User-Roles` que no incluye `ORGANIZADOR`
- **WHEN** solicita crear un evento
- **THEN** responde `401 Unauthorized` si falta identidad o roles, o `403 Forbidden` si no incluye el rol organizador, y no crea el evento

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
- **WHEN** el organizador dueño, autenticado con `X-User-Roles` que incluye `ORGANIZADOR`, asigna un precio mayor o igual a cero
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
- **GIVEN** una solicitud para cambiar un precio sin `X-User-Id` o `X-User-Roles`
- **WHEN** el servicio procesa la solicitud
- **THEN** responde `401 Unauthorized` y no modifica el evento

### Requirement: Publicar eventos con disponibilidad inicializada
El servicio SHALL publicar un evento solo cuando todos los sectores tengan precio y Reservas confirme la inicialización de disponibilidad.

#### Scenario: Reservas confirma inicialización
- **GIVEN** un evento en borrador con precio para cada sector y cuyo organizador autenticado, con `X-User-Roles` que incluye `ORGANIZADOR`, es el dueño
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
- **GIVEN** un evento en borrador y una solicitud de otro organizador o sin identidad o `X-User-Roles`
- **WHEN** intenta publicarlo
- **THEN** responde `403 Forbidden` si es otro organizador o no incluye `ORGANIZADOR`, o `401 Unauthorized` si falta la identidad o la lista de roles, y el evento permanece en borrador

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
- **WHEN** Compras consulta `GET /internal/eventos/{eventoId}` sin headers de usuario
- **THEN** responde `200 OK` con `eventoId`, `nombre`, `fechaInicio` en ISO-8601 UTC, `estado` en minúsculas y sectores con `sectorId`, nombre y precio decimal, cualquiera sea el estado del evento

#### Scenario: Consulta interna de borrador con precio aún no definido
- **GIVEN** un evento en `borrador` con uno o más sectores sin precio
- **WHEN** Compras consulta `GET /internal/eventos/{eventoId}` sin headers de usuario
- **THEN** responde `200 OK` y el precio de cada sector todavía no configurado es `null`

#### Scenario: Compras consulta evento inexistente
- **GIVEN** un identificador de evento que no existe
- **WHEN** Compras consulta `GET /internal/eventos/{eventoId}` sin headers de usuario
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

### Requirement: Buscar eventos publicados y agotados
El servicio SHALL ofrecer `GET /eventos` sin requerir token ni identidad. SHALL permitir filtrar por tipo, organizador, participante, ciudad y límites inclusivos de fecha/hora. SHALL combinar todos los filtros recibidos con AND y SHALL devolver solo estados `publicado` y `agotado`.

#### Scenario: Reemplazar el método GET actualmente no soportado
- **GIVEN** la ruta `GET /eventos`, que antes de este cambio responde `405 Method Not Allowed`
- **WHEN** un consumidor sin token consulta la ruta con parámetros válidos
- **THEN** el servicio procesa la búsqueda y responde `200 OK` en lugar de `405`

#### Scenario: Buscar sin criterios
- **GIVEN** hay eventos `borrador`, `cancelado`, `finalizado`, `publicado` y `agotado`
- **WHEN** un consumidor sin token consulta `GET /eventos`
- **THEN** responde `200 OK` únicamente con eventos `publicado` y `agotado`, ordenados por fecha ascendente y paginados

#### Scenario: Buscar combinando criterios
- **GIVEN** eventos publicados o agotados con distintos tipos, organizadores, participantes, ciudades y fechas
- **WHEN** un consumidor sin token busca usando más de un criterio en `GET /eventos`
- **THEN** la respuesta incluye solo eventos que cumplen todos los criterios y responde `200 OK`

#### Scenario: Rango de fechas inclusivo
- **GIVEN** eventos que comienzan exactamente en `fechaDesde`, exactamente en `fechaHasta` y fuera del rango
- **WHEN** se consulta `GET /eventos` con ambos límites ISO-8601 UTC
- **THEN** incluye los dos eventos en los límites, excluye el que está fuera y responde `200 OK`

#### Scenario: Rechazar rango de fechas invertido o mal formado
- **GIVEN** una consulta con fecha inválida o `fechaDesde` posterior a `fechaHasta`
- **WHEN** se procesa `GET /eventos`
- **THEN** responde `400 Bad Request` con el formato JSON estándar

#### Scenario: Devolver página vacía sin coincidencias
- **GIVEN** no hay eventos visibles que cumplan los filtros
- **WHEN** un consumidor realiza la búsqueda
- **THEN** responde `200 OK` con `contenido` vacío y metadatos de paginación, sin error

#### Scenario: No mostrar borradores, cancelados ni finalizados en búsqueda
- **GIVEN** hay eventos `borrador`, `cancelado`, `finalizado`, `publicado` y `agotado`
- **WHEN** un consumidor consulta la búsqueda sin token
- **THEN** los resultados no contienen los tres primeros estados y responden `200 OK`

#### Scenario: Devolver lista vacía si no hay eventos visibles
- **GIVEN** no hay eventos `publicado` ni `agotado`
- **WHEN** un consumidor consulta `GET /eventos` sin filtros y sin token
- **THEN** responde `200 OK` con `contenido: []`

#### Scenario: No exigir autenticación para la búsqueda
- **GIVEN** una solicitud sin `X-User-Id` ni `X-User-Roles` y parámetros válidos
- **WHEN** consulta `GET /eventos`
- **THEN** responde `200 OK` con los resultados públicos

#### Scenario: Ignorar roles desconocidos en consultas públicas
- **GIVEN** una solicitud a la búsqueda o al detalle de un evento publicado/agotado que incluye una identidad y `X-User-Roles` con roles desconocidos
- **WHEN** consulta la ruta pública con parámetros válidos
- **THEN** los roles desconocidos no se usan para autorizar y responde `200 OK`, como una solicitud anónima

#### Scenario: Validar paginación
- **GIVEN** una búsqueda con `page` negativo, `size` menor que uno o `size` mayor que cien
- **WHEN** se procesa la solicitud
- **THEN** responde `400 Bad Request` con el error estándar

### Requirement: Ordenar y paginar la búsqueda de eventos
El servicio SHALL permitir `orden=fecha`, `orden=precio` o `orden=relevancia`; por defecto SHALL usar `fecha`. SHALL usar `direccion=asc|desc` únicamente para `precio`. SHALL aceptar `q` opcional para buscar texto en nombre, descripción y participantes.

#### Scenario: Ordenar por fecha
- **GIVEN** hay eventos visibles con distintas fechas
- **WHEN** se consulta sin `orden` o con `orden=fecha`
- **THEN** devuelve primero el evento de fecha más cercana, independientemente de `direccion`, y responde `200 OK`

#### Scenario: Ordenar por precio ascendente o descendente
- **GIVEN** hay eventos visibles con distintos valores `precioMinimo`
- **WHEN** se consulta con `orden=precio` y `direccion=asc` o `direccion=desc`
- **THEN** devuelve los eventos por `precioMinimo` en la dirección indicada y responde `200 OK`

#### Scenario: Ordenar por relevancia con texto
- **GIVEN** hay eventos cuyo nombre, descripción o participantes coinciden con `q` en distintos grados
- **WHEN** se consulta con `orden=relevancia` y `q`
- **THEN** devuelve puntaje de texto descendente y desempata por fecha ascendente, con `200 OK`

#### Scenario: Relevancia sin texto equivale a fecha ascendente
- **GIVEN** hay eventos visibles con distintas fechas
- **WHEN** se consulta con `orden=relevancia` y sin `q`
- **THEN** los ordena por fecha ascendente y responde `200 OK`

#### Scenario: Rechazar orden no soportado
- **GIVEN** una consulta con valor de `orden` distinto de `fecha`, `precio` o `relevancia`
- **WHEN** se procesa la búsqueda
- **THEN** responde `400 Bad Request` con el formato de error estándar

#### Scenario: Rechazar dirección no soportada
- **GIVEN** una consulta con `direccion` distinta de `asc` o `desc`
- **WHEN** se procesa la búsqueda
- **THEN** responde `400 Bad Request` con el formato de error estándar

#### Scenario: Aplicar valores predeterminados y metadatos de página
- **GIVEN** hay más resultados que el tamaño de página predeterminado
- **WHEN** se consulta sin `page` ni `size`
- **THEN** se usa página `0` y tamaño `20`, y la respuesta incluye `contenido`, `pagina`, `tamano`, `totalElementos` y `totalPaginas`

### Requirement: Consultar el detalle público de un evento
`GET /eventos/{eventoId}` SHALL ser público sin token para eventos `publicado` o `agotado`. Un evento `borrador` SHALL requerir identidad completa del titular `ORGANIZADOR` o de un usuario `ADMIN` (`401` sin identidad, `404` sin autorización). Estados `cancelado` y `finalizado` responderán `200` sin consultar Reservas. La ruta pública SHALL ser independiente de `GET /internal/eventos/{eventoId}`.

#### Scenario: Consultar evento público existente
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** cualquiera consulta el detalle sin token
- **THEN** responde `200 OK` con nombre, descripción, tipo, `organizadorId`, participantes, fecha y hora, lugar, sectores y precios

#### Scenario: Consultar un borrador propio
- **GIVEN** un evento `borrador` perteneciente al organizador identificado por `X-User-Id` y una lista de roles que contiene `ORGANIZADOR`
- **WHEN** ese organizador autenticado consulta el detalle
- **THEN** responde `200 OK` con los datos del evento y no consulta disponibilidad a Reservas

#### Scenario: Administrador consulta un borrador
- **GIVEN** un evento `borrador` y una solicitud autenticada cuya lista de roles contiene `ADMIN`
- **WHEN** el administrador consulta el detalle
- **THEN** responde `200 OK` con los datos del evento y no consulta disponibilidad a Reservas

#### Scenario: Rechazar consulta de borrador sin token
- **GIVEN** un evento `borrador`
- **WHEN** consulta el detalle sin `X-User-Id` o sin `X-User-Roles`
- **THEN** responde `401 Unauthorized`

#### Scenario: Ocultar borrador a otros usuarios
- **GIVEN** un evento `borrador` que pertenece a otro organizador
- **WHEN** un cliente u otro organizador consulta su detalle
- **THEN** responde `404 Not Found` igual que para un evento inexistente

#### Scenario: Evento inexistente
- **GIVEN** un identificador de evento no registrado
- **WHEN** se consulta el detalle
- **THEN** responde `404 Not Found` con el formato de error estándar

#### Scenario: Consultar evento cancelado o finalizado sin disponibilidad
- **GIVEN** un evento `cancelado` o `finalizado`
- **WHEN** un consumidor consulta su detalle sin token
- **THEN** responde `200 OK` con los datos del evento y sin consultar disponibilidad a Reservas

### Requirement: Incluir disponibilidad de Reservas en el detalle público
Para eventos `publicado` o `agotado`, el servicio SHALL consultar `GET /disponibilidad/eventos/{eventoId}` en Reservas y combinar sus resultados con los sectores del evento mediante `sectorId`. Para cualquier otro estado no SHALL consultar a Reservas. Si Reservas no responde, falla o devuelve `404` o `500`, el detalle SHALL responder `200 OK` con disponibilidad `null` en los sectores afectados.

#### Scenario: Combinar disponibilidad por sectorId
- **GIVEN** un evento `publicado` con sectores y Reservas responde `200` con disponibilidad
- **WHEN** se consulta el detalle público
- **THEN** cada sector se combina con la disponibilidad que tenga el mismo `sectorId`, conservando precio y configuración local, y responde `200 OK`

#### Scenario: Incluir asientos disponibles numerados
- **GIVEN** un evento `agotado` con un sector numerado y Reservas devuelve sus asientos disponibles
- **WHEN** se consulta el detalle
- **THEN** ese sector muestra `cantidadDisponible` y `asientosDisponibles` con `fila` y `numero` enteros

#### Scenario: Reservas no responde o falla
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** Reservas vence el timeout, no responde o devuelve `404` o `500`
- **THEN** el detalle igual responde `200 OK` y cada disponibilidad no obtenida es `null`

#### Scenario: No consultar disponibilidad para otros estados
- **GIVEN** un evento `borrador`, `cancelado` o `finalizado` visible para el usuario
- **WHEN** se consulta su detalle
- **THEN** responde `200 OK` con disponibilidad `null` y no realiza una llamada a Reservas

#### Scenario: Reservas responde con disponibilidad por sector
- **GIVEN** Reservas recibe `GET /disponibilidad/eventos/{eventoId}`
- **WHEN** existe disponibilidad
- **THEN** responde `200` con `eventoId` y sectores con `sectorId`, `tipo`, `capacidadTotal`, `cantidadDisponible` y, para sectores numerados, `asientosDisponibles` con fila y número enteros

#### Scenario: Callback con el campo agotado ausente
- **GIVEN** una llamada a `PUT /eventos/{eventoId}/disponibilidad` con body `{}`
- **WHEN** el servicio valida el callback
- **THEN** responde `400 Bad Request` con el formato JSON de error estándar

#### Scenario: Callback con agotado como texto no interpretable
- **GIVEN** una llamada al callback con body `{"agotado":"si"}`
- **WHEN** el servicio deserializa y valida el body
- **THEN** responde `400 Bad Request` con el formato JSON de error estándar

#### Scenario: Callback con agotado null
- **GIVEN** una llamada al callback con body `{"agotado":null}`
- **WHEN** el servicio valida el callback
- **THEN** responde `400 Bad Request` con el formato JSON de error estándar

### Requirement: Responder errores inesperados de forma segura
Ante una excepción inesperada, el servicio SHALL responder `500 Internal Server Error` con el formato JSON estándar y un mensaje genérico, sin exponer stack traces ni mensajes internos de MongoDB.

#### Scenario: Error interno inesperado
- **GIVEN** una operación produce una excepción inesperada
- **WHEN** el servicio construye la respuesta HTTP
- **THEN** responde `500 Internal Server Error` con `timestamp`, `status`, `error` y `mensaje` genérico, omite `campos` si no aplica y no expone stack traces ni mensajes internos de MongoDB

### Requirement: Interpretar identidad y roles del Gateway en eventos
El servicio SHALL leer `X-User-Id` y `X-User-Roles`. SHALL separar los roles por comas, recortar sus espacios externos y evaluar la lista completa, sin precedencia. Para las rutas autenticadas, la falta de identidad o roles responderá `401`; no tener un rol permitido responderá `403`. El header singular `X-User-Role` deja de ser fuente de autorización.

#### Scenario: Aceptar usuarios con varios roles
- **GIVEN** un organizador titular autenticado con `X-User-Roles: CLIENTE, ORGANIZADOR`
- **WHEN** administra su evento
- **THEN** el servicio reconoce el rol `ORGANIZADOR` y comprueba además la titularidad

### Requirement: Mantener titularidad y permisos administrativos de eventos
Crear, configurar y publicar eventos SHALL requerir el rol `ORGANIZADOR`; administrar un evento existente también SHALL requerir que `X-User-Id` coincida con `organizadorId`. Las operaciones administrativas existentes, incluida la cancelación, SHALL requerir `ADMIN`, sin alias.

#### Scenario: Rechazar administración de un evento ajeno
- **GIVEN** una solicitud con rol `ORGANIZADOR` cuyo `X-User-Id` no coincide con el `organizadorId` del evento
- **WHEN** intenta modificar o publicar ese evento
- **THEN** el servicio responde `403 Forbidden` y no modifica el evento

#### Scenario: Rechazar rol insuficiente
- **GIVEN** una solicitud autenticada con `X-User-Roles: CLIENTE`
- **WHEN** intenta crear, configurar o publicar un evento
- **THEN** el servicio responde `403 Forbidden`

#### Scenario: Permitir cancelación solo a ADMIN
- **GIVEN** una solicitud autenticada sin el rol `ADMIN`
- **WHEN** intenta cancelar un evento
- **THEN** el servicio responde `403 Forbidden` y no cancela el evento

### Requirement: Mantener rutas internas sin autenticación de usuario
Las rutas `/internal/...` y los callbacks internos SHALL permanecer sin autenticación de usuario.

#### Scenario: Mantener las rutas internas sin identidad de usuario
- **GIVEN** una llamada de otro servicio a `/internal/eventos/{eventoId}` o un callback interno sin headers de usuario
- **WHEN** el servicio procesa la llamada
- **THEN** no exige `X-User-Id` ni `X-User-Roles`

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
