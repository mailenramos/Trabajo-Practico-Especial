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
Solo usuarios autenticados cuya lista `X-User-Roles` incluya `ADMIN` SHALL suspender o reactivar ventas y cancelar eventos elegibles. La suspensión y reactivación SHALL NOT cambiar el estado formal. La intención de cancelar SHALL persistirse antes de llamar a Reservas. Si una operación local origina avisos, SHALL guardar el cambio y los avisos outbox en la misma transacción MongoDB. Un fallo posterior de entrega SHALL NOT revertir el cambio ni afectar la respuesta exitosa.

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
El servicio SHALL ofrecer una consulta interna independiente del detalle público para Compras y otros servicios, sin headers de usuario y no enrutable por el API Gateway. SHALL leer los datos actuales del evento en cada solicitud, sin caché. SHALL conservar el contrato existente de Compras y agregar `organizadorId`, `requiereFila`, `tipo`, `fechaHora`, `ciudad` y los datos de sector ya persistidos.

#### Scenario: Compras consulta evento existente
- **GIVEN** un evento existente en cualquier estado, con datos de organizador y sectores
- **WHEN** otro servicio consulta `GET /internal/eventos/{eventoId}` sin headers de usuario
- **THEN** responde `200 OK` con `eventoId`, `nombre`, `tipo`, `fechaHora` en ISO-8601 UTC, `ciudad`, `estado` en minúsculas, `organizadorId`, `requiereFila` y sectores con `sectorId`, `nombre`, `tipo`, `filas`, `asientosPorFila`, `capacidad` y `precio`
- **AND** conserva `fechaInicio` en ISO-8601 UTC como alias del instante `fechaHora` para Compras

#### Scenario: Consultar el indicador actualizado sin caché
- **GIVEN** un evento cuyo indicador se actualizó correctamente
- **WHEN** otro servicio consulta `GET /internal/eventos/{eventoId}`
- **THEN** la respuesta refleja el valor actual persistido de `requiereFila`

#### Scenario: Consulta interna de borrador con precio aún no definido
- **GIVEN** un evento en `borrador` con uno o más sectores sin precio
- **WHEN** Compras consulta `GET /internal/eventos/{eventoId}` sin headers de usuario
- **THEN** responde `200 OK` y el precio de cada sector todavía no configurado es `null`

#### Scenario: Compras consulta evento inexistente
- **GIVEN** un identificador de evento que no existe
- **WHEN** otro servicio consulta `GET /internal/eventos/{eventoId}` sin headers de usuario
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
Crear, configurar y publicar eventos SHALL requerir el rol `ORGANIZADOR`; administrar un evento existente también SHALL requerir que `X-User-Id` coincida con `organizadorId`, excepto que la modificación de fecha y lugar también SHALL permitir a `ADMIN` no titular. Las operaciones administrativas existentes, incluida la cancelación, SHALL requerir `ADMIN`, sin alias.

#### Scenario: Rechazar administración de un evento ajeno
- **GIVEN** una solicitud con rol `ORGANIZADOR` cuyo `X-User-Id` no coincide con el `organizadorId` del evento y no incluye `ADMIN`
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

### Requirement: Consultar eventos publicados para recomendaciones
MS1 SHALL ofrecer `GET /internal/eventos-publicados` para Preferencias y recomendaciones (MS8), sin headers de usuario y sin publicar la ruta por el API Gateway. SHALL devolver solo eventos en estado `publicado` o `agotado`, sin filtrar por fecha. Cada evento SHALL incluir `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` del lugar, `fechaHora` en ISO-8601 UTC y `estado`. MS8 es responsable de excluir eventos finalizados, cancelados y con fecha pasada.

#### Scenario: MS8 consulta eventos sin identidad de usuario
- **GIVEN** existen eventos en distintos estados
- **WHEN** MS8 consulta `GET /internal/eventos-publicados` sin headers de usuario
- **THEN** responde `200 OK` con únicamente eventos `publicado` o `agotado` y los campos acordados, sin requerir autenticación de usuario

### Requirement: Ordenar y paginar la consulta interna de eventos para MS8
La consulta SHALL ordenar por `fechaHora` ascendente y por ID del evento ascendente como desempate. SHALL usar `page` cero-based, default `0`, y `size` default `100` con máximo `200`. Los valores inválidos SHALL responder `400 Bad Request` con el formato de error estándar.

#### Scenario: Orden estable para paginar
- **GIVEN** existen eventos `publicado` o `agotado` con fechas iguales
- **WHEN** MS8 consulta páginas consecutivas
- **THEN** los resultados se ordenan por `fechaHora` ascendente y, a igual fecha, por ID del evento ascendente

#### Scenario: Paginación de eventos para MS8
- **GIVEN** existen más de 100 eventos `publicado` o `agotado`
- **WHEN** MS8 consulta sin `page` ni `size`
- **THEN** se usa `page=0` y `size=100`, y la respuesta incluye `contenido`, `pagina`, `tamano`, `totalElementos` y `totalPaginas`

#### Scenario: Rechazar tamaño de página mayor que el máximo
- **GIVEN** MS8 solicita eventos con `size` mayor que 200
- **WHEN** se procesa la consulta
- **THEN** responde `400 Bad Request` con el formato de error estándar

#### Scenario: Rechazar tamaño de página menor que uno
- **GIVEN** MS8 solicita eventos con `size` menor que 1
- **WHEN** se procesa la consulta
- **THEN** responde `400 Bad Request` con el formato de error estándar

#### Scenario: Rechazar número de página negativo
- **GIVEN** MS8 solicita eventos con `page` negativo
- **WHEN** se procesa la consulta
- **THEN** responde `400 Bad Request` con el formato de error estándar

### Requirement: Notificar publicaciones de eventos a Preferencias mediante outbox
Después de que Reservas confirme la inicialización, MS1 SHALL guardar en una transacción MongoDB el evento publicado y un aviso outbox tipo `PUBLICACION` con ID `publicacion-{eventoId}`. Tras el commit, SHALL intentar entregarlo fuera de la transacción por el dispatcher general a `POST {PREFERENCIAS_BASE_URL}/internal/preferencias/eventos-publicados`. Los fallos no SHALL revertir la publicación ni alterar su respuesta; los reintentos SHALL ser asíncronos por el dispatcher.

#### Scenario: Entregar el aviso de publicación por el dispatcher
- **GIVEN** un evento en borrador listo para publicar y Reservas confirma la inicialización
- **WHEN** se confirma la transacción local y el dispatcher entrega el aviso
- **THEN** envía `X-Aviso-Id: publicacion-{eventoId}` y un payload con `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` y `fechaHora` ISO-8601 UTC al destino configurado

#### Scenario: Publicar un evento sin participantes
- **GIVEN** un evento listo para publicar sin participantes
- **WHEN** Reservas confirma la inicialización y MS1 registra el aviso `PUBLICACION`
- **THEN** el payload contiene `participantes` como una lista vacía y no como `null`

#### Scenario: Preferencias no está disponible al publicar
- **GIVEN** Reservas confirmó la inicialización y la transacción local guardó el evento y el aviso
- **WHEN** Preferencias falla o no responde al intento de entrega
- **THEN** el evento permanece publicado, el administrador conserva la respuesta exitosa y el aviso queda sujeto a los reintentos del dispatcher general

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

### Requirement: Validar el token de outbox según el perfil
MS1 SHALL fallar al arrancar con perfil `docker` activo si `servicio.token` está vacío o contiene solo espacios. En otros perfiles SHALL permitir el arranque y emitir un `WARN` sin revelar el valor. Compose SHALL pasar `SERVICIO_TOKEN` al servicio `lugares` sin default.

#### Scenario: Bloquear arranque Docker sin token
- **GIVEN** el perfil `docker` está activo, solo o con otros perfiles, y el token está vacío o contiene solo espacios
- **WHEN** MS1 inicia
- **THEN** falla el arranque con el mensaje `SERVICIO_TOKEN no configurada`

#### Scenario: Advertir sin bloquear otros perfiles
- **GIVEN** el perfil `docker` no está activo y el token está vacío o contiene solo espacios
- **WHEN** MS1 inicia
- **THEN** el arranque continúa y se registra un `WARN` sin exponer el token

### Requirement: Mantener la política de fila en el evento
MS1 SHALL ser la autoridad del indicador booleano `requiereFila`. Los eventos nuevos SHALL tener valor inicial `false` y los documentos existentes sin el campo SHALL leerse como `false` sin migración. La pausa de fila gestionada por MS7 SHALL NOT modificar el indicador; los eventos con `requiereFila=false` SHALL seguir el proceso habitual de reservas.

#### Scenario: Leer evento anterior sin el campo de fila
- **GIVEN** un evento persistido sin el campo `requiereFila`
- **WHEN** otro servicio lo consulta mediante `GET /internal/eventos/{eventoId}`
- **THEN** responde `200 OK` con `requiereFila: false` sin migrar el documento

#### Scenario: Evento nuevo sin fila
- **GIVEN** un evento nuevo
- **WHEN** se consulta su política de fila
- **THEN** `requiereFila` es `false` y se mantiene el proceso habitual de reservas

#### Scenario: Pausar la fila no cambia la política
- **GIVEN** MS7 pausa una fila virtual
- **WHEN** se consulta el indicador en MS1
- **THEN** conserva su valor previo; MS1 no ofrece un endpoint para pausar la fila

### Requirement: Actualizar internamente la política de fila
MS1 SHALL ofrecer a MS7 `PUT /internal/eventos/{eventoId}/politica-fila`, sin headers de usuario ni exposición por el Gateway. El body SHALL contener `requiereFila` como booleano obligatorio. La operación SHALL ser idempotente y SHALL NOT modificar el evento ni incrementar su versión si el valor coincide, salvo que activar un evento cancelado SHALL responder `409`. Desactivar un evento cancelado SHALL estar permitido. No se agrega validación entrante de `X-Service-Token`.

#### Scenario: Activar la fila
- **GIVEN** un evento no cancelado con `requiereFila=false`
- **WHEN** MS7 envía `{"requiereFila":true}` sin headers de usuario
- **THEN** responde `200 OK` con `eventoId` y `requiereFila: true`, y persiste el nuevo valor

#### Scenario: Desactivar la fila
- **GIVEN** un evento no cancelado con `requiereFila=true`
- **WHEN** MS7 envía `{"requiereFila":false}` sin headers de usuario
- **THEN** responde `200 OK` con `eventoId` y `requiereFila: false`, y persiste el nuevo valor

#### Scenario: Repetir la misma actualización
- **GIVEN** un evento cuyo indicador ya coincide con el valor solicitado y no se solicita activar un cancelado, incluso si es un documento antiguo sin el campo y se solicita `false`
- **WHEN** MS7 repite la actualización
- **THEN** responde `200 OK` con el valor actual, no modifica el documento y no incrementa su versión

#### Scenario: Rechazar actualización de evento cancelado
- **GIVEN** un evento cancelado
- **WHEN** MS7 solicita activar la fila, incluso si el valor ya es `true`
- **THEN** responde `409 Conflict` y no modifica el evento ni su versión

#### Scenario: Desactivar la fila de un evento cancelado
- **GIVEN** un evento cancelado con `requiereFila=true`, `false` o sin el campo guardado
- **WHEN** MS7 solicita `{"requiereFila":false}`
- **THEN** responde `200 OK` con el indicador `false`; si estaba en `true` persiste el cambio y, si ya era `false` o faltaba el campo, no escribe ni incrementa la versión

#### Scenario: Rechazar evento inexistente
- **GIVEN** un evento que no existe
- **WHEN** MS7 solicita actualizar la política, incluso con un body inválido
- **THEN** responde `404 Not Found`

#### Scenario: Rechazar body inválido
- **GIVEN** un evento existente en cualquier estado y un body ausente, JSON inválido, o `requiereFila` omitido, `null` o de un tipo distinto de booleano
- **WHEN** MS7 solicita actualizar la política
- **THEN** responde `400 Bad Request` con el formato de error estándar, sin modificar el evento

#### Scenario: Proteger una actualización concurrente
- **GIVEN** una actualización que cambia el indicador y una modificación concurrente del evento
- **WHEN** el control optimista detecta una versión obsoleta
- **THEN** responde `409 Conflict` sin sobrescribir el cambio concurrente y la operación puede reintentarse

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

### Requirement: Modificar fecha y lugar de un evento
MS1 SHALL ofrecer `PUT /eventos/{eventoId}` con `fechaHora` ISO-8601 UTC y/o `lugarId`. SHALL permitirlo al `ORGANIZADOR` titular o a `ADMIN`, evaluando todos los roles. SHALL conservar los estados públicos existentes y responder con el evento actualizado.

#### Scenario: Modificar fecha y lugar
- **GIVEN** un evento publicado y un organizador titular autenticado con roles `CLIENTE, ORGANIZADOR`
- **WHEN** envía una fecha futura y un lugar existente distintos de los actuales
- **THEN** responde `200`, guarda fecha y lugar y actualiza la copia de ciudad a la del nuevo lugar

#### Scenario: Modificar solo un campo
- **GIVEN** un evento elegible
- **WHEN** se envía solo una fecha futura o solo un lugar existente
- **THEN** responde `200`, modifica el campo enviado y conserva el otro

#### Scenario: Autorizar un administrador no titular
- **GIVEN** un usuario autenticado distinto del titular con roles `CLIENTE, ADMIN`
- **WHEN** modifica un evento elegible
- **THEN** responde `200` sin exigir el rol de organizador ni la titularidad

#### Scenario: Rechazar identidad faltante o rol insuficiente
- **WHEN** se solicita la modificación sin identidad completa o sin un rol permitido
- **THEN** responde respectivamente `401` o `403`, sin modificar el evento

#### Scenario: Rechazar organizador ajeno
- **GIVEN** un organizador autenticado sin rol `ADMIN` que no es el titular
- **WHEN** solicita la modificación
- **THEN** responde `403` sin modificar el evento

#### Scenario: Evento inexistente
- **WHEN** un usuario autenticado autorizado solicita modificar un evento inexistente
- **THEN** responde `404`

#### Scenario: Rechazar datos inválidos
- **WHEN** el body está vacío, no contiene ninguno de los campos, contiene fecha inválida o no futura, o identifica un lugar inexistente
- **THEN** responde `400` sin modificar el evento ni crear un aviso

#### Scenario: Rechazar estados terminales e intención pendiente
- **GIVEN** un evento cancelado, finalizado o con una intención de cancelación pendiente
- **WHEN** se solicita su modificación
- **THEN** responde `409` sin modificarlo ni crear un aviso

#### Scenario: No escribir si no hay cambios reales
- **GIVEN** un evento elegible
- **WHEN** se envían sus valores actuales válidos
- **THEN** responde `200` con el evento sin escribirlo, incrementar su versión ni crear avisos

#### Scenario: Conservar sectores y disponibilidad al cambiar lugar
- **GIVEN** un evento con sectores y eventualmente disponibilidad inicializada
- **WHEN** se modifica su lugar
- **THEN** se conservan los sectores, precios y disponibilidad existentes sin migrarlos al nuevo lugar

#### Scenario: Consultas reflejan datos confirmados
- **GIVEN** una modificación confirmada
- **WHEN** se consulta el detalle público, `GET /internal/eventos/{eventoId}` o se repite una búsqueda afectada
- **THEN** las respuestas reflejan los nuevos datos aplicables a sus contratos y la búsqueda no reutiliza la entrada local anterior

### Requirement: Persistir aviso completo de modificación
Para un cambio real de fecha u horario o lugar de un evento publicado o agotado, MS1 SHALL guardar evento y aviso en una misma transacción MongoDB. El aviso SHALL contener el estado completo posterior y usar la versión del evento guardado.

#### Scenario: Avisar modificación publicada
- **GIVEN** un evento publicado o agotado que se modifica realmente
- **WHEN** se confirma la transacción
- **THEN** existe un aviso con ID `modificacion-{eventoId}-v{version}`, destino `POST {COMPRAS_BASE_URL}/internal/eventos-modificados` y body `eventoId`, `version`, `fechaHora`, `lugar` y `ciudad`
- **AND** `version` es un entero mayor o igual a 1 posterior a la escritura, `fechaHora` es ISO-8601 UTC y `lugar` es el nombre actual, no su ID

#### Scenario: Enviar siempre estado completo
- **GIVEN** un cambio únicamente de fecha o únicamente de lugar
- **WHEN** se crea el aviso
- **THEN** incluye fecha, nombre de lugar y ciudad actuales, incluso los campos no modificados

#### Scenario: Modificar borrador sin aviso
- **GIVEN** un evento en borrador con un cambio real válido
- **WHEN** se confirma la modificación
- **THEN** se guardan los datos y la versión aumenta, sin crear aviso para Compras

#### Scenario: Revertir ante fallo del aviso
- **GIVEN** una modificación de un evento publicado o agotado
- **WHEN** falla la escritura del evento o la inserción del aviso antes del commit
- **THEN** no se persiste ni el cambio del evento ni el aviso y no se invalida la caché por esa transacción

#### Scenario: Evitar escrituras concurrentes obsoletas
- **GIVEN** una modificación concurrente o una intención de cancelación registrada mientras se procesa la solicitud
- **WHEN** el control optimista detecta una versión obsoleta
- **THEN** responde `409` y revierte el cambio y su aviso, sin sobrescribir la escritura concurrente

#### Scenario: Incrementar versiones en escrituras sucesivas
- **GIVEN** un evento con versión persistida
- **WHEN** se producen escrituras reales sucesivas del evento
- **THEN** cada escritura incrementa su versión y cada aviso de modificación usa la versión confirmada correspondiente

### Requirement: Entregar avisos de modificación a Compras
MS1 SHALL entregar el aviso de modificación fuera de la transacción mediante la outbox existente, con los headers comunes y el ID y payload originales en cada intento.

#### Scenario: Compras acepta o ya procesó la versión
- **WHEN** Compras responde `202`, `200` o cualquier otro `2xx`
- **THEN** el aviso se marca entregado; Compras deduplica por `eventoId` y `version` y puede ignorar versiones anteriores

#### Scenario: Fallos definitivos
- **WHEN** Compras responde `400` o `403`
- **THEN** el aviso pasa a `ERROR_FINAL` y no se reintenta automáticamente

#### Scenario: Reintentar fallos temporales
- **WHEN** la entrega falla por `5xx`, timeout o error de conexión
- **THEN** se reintenta con hasta 8 reclamos y backoff de 30 segundos que se duplica hasta 15 minutos, sin revertir el evento

#### Scenario: Conservar encabezados comunes
- **WHEN** se entrega o reentrega un aviso
- **THEN** se envían `X-Service-Token`, `X-Aviso-Id` y `X-Correlation-Id`
