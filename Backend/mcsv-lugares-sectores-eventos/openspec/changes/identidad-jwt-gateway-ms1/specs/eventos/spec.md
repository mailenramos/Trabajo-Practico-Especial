## ADDED Requirements

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

## MODIFIED Requirements

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
El servicio SHALL permitir solo a un usuario autenticado cuya lista `X-User-Roles` incluya `ADMIN` suspender o reactivar ventas y cancelar eventos elegibles, sin mezclar la suspensión con el estado formal.

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
- **THEN** pasa a `cancelado`, se registra el aviso con motivo y `canceladoEn` UTC, se intenta notificar a Compras y el administrador recibe `200 OK`

#### Scenario: Cancelar con motivo omitido
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** un administrador autenticado con `X-User-Roles` que incluye `ADMIN` cancela sin body o sin el campo `motivo` y Reservas confirma con `204`
- **THEN** se usa un motivo por defecto, se registra el instante UTC de cancelación, y el evento queda `cancelado` con respuesta `200 OK`

#### Scenario: Compras acepta el aviso de cancelación
- **GIVEN** Reservas confirmó la cancelación y el evento local ya está `cancelado`
- **WHEN** Compras responde `202 Accepted` al aviso
- **THEN** el aviso queda registrado como entregado y el administrador recibe `200 OK`

#### Scenario: Compras ya procesó el aviso de cancelación
- **GIVEN** Reservas confirmó la cancelación y el evento local ya está `cancelado`
- **WHEN** Compras responde `200 OK` porque ya procesó el aviso
- **THEN** el aviso queda registrado como entregado y no se vuelve a reenviar

#### Scenario: No cancelar si Reservas falla
- **GIVEN** un evento `publicado` o `agotado`
- **WHEN** Reservas falla o no responde antes del cambio local
- **THEN** el evento conserva su estado actual, no se registra aviso de cancelación a Compras y se responde `503 Service Unavailable`

#### Scenario: Compras no responde al aviso de cancelación
- **GIVEN** un evento cancelado luego de la confirmación de Reservas
- **WHEN** Compras falla, no responde o devuelve un error al aviso
- **THEN** la cancelación sigue hecha, el administrador recibe `200 OK` y el aviso queda persistido como pendiente de reenvío

#### Scenario: Reenviar aviso de cancelación pendiente
- **GIVEN** un aviso de cancelación persistido como pendiente
- **WHEN** el proceso programado lo reenvía y Compras responde `202` o `200`
- **THEN** el aviso queda registrado como entregado y no se vuelve a enviar

#### Scenario: Rechazar cancelación de evento finalizado o cancelado
- **GIVEN** un evento `finalizado` o `cancelado`
- **WHEN** un administrador intenta cancelarlo
- **THEN** no cambia el evento ni duplica el aviso y responde `409 Conflict`

#### Scenario: Rechazar acción administrativa a otro rol o sin identidad
- **GIVEN** una solicitud sin identidad o cuya lista `X-User-Roles` no incluye `ADMIN`
- **WHEN** intenta suspender, reactivar o cancelar un evento
- **THEN** responde `401 Unauthorized` si falta `X-User-Id` o `X-User-Roles`, o `403 Forbidden` si la lista no incluye el rol administrador, sin modificar el evento

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

### Requirement: Consultar el detalle público de un evento
El servicio SHALL ofrecer `GET /eventos/{eventoId}` sin token para eventos en estado `publicado` o `agotado`. SHALL devolver los datos del evento, lugar y sectores con precio y disponibilidad. Un evento `borrador` SHALL ser visible únicamente con identidad autenticada y si `X-User-Roles` incluye `ADMIN` o incluye `ORGANIZADOR` y `X-User-Id` coincide con `organizadorId`. La consulta de un borrador sin identidad completa SHALL responder `401 Unauthorized`; para otro usuario o rol sin permiso SHALL responder `404 Not Found` para no revelar su existencia. Los estados `cancelado` y `finalizado` SHALL conservar el comportamiento previo: responder `200 OK` con los datos del evento y sin consultar disponibilidad a Reservas. La ruta pública SHALL ser independiente de `GET /internal/eventos/{eventoId}`.

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

## REMOVED Requirements

### Requirement: Normalizar el rol administrativo del ciclo de vida
Se elimina el alias `administrador` de `X-User-Role`; la autorización administrativa se determina exclusivamente por la presencia de `ADMIN` en `X-User-Roles`.

**Reason**: El Gateway envía los roles como una lista en `X-User-Roles`; mantener un alias singular legado generaría reglas distintas entre operaciones y consultas.

**Migration**: El Gateway y los clientes deben enviar `ADMIN` como elemento de `X-User-Roles` para las operaciones administrativas. Dejar de enviar `X-User-Role: administrador`.
