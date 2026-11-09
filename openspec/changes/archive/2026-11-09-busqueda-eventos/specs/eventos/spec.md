# Spec Delta

## Purpose

Definir la búsqueda pública, el ordenamiento, la paginación y el detalle de eventos, incluyendo la visibilidad de borradores y la disponibilidad opcional consultada a Reservas.

## ADDED Requirements

### Requirement: Buscar eventos publicados y agotados
El servicio SHALL ofrecer `GET /eventos` a clientes, organizadores y administradores autenticados. SHALL permitir filtrar por tipo, organizador, participante, ciudad y límites inclusivos de fecha/hora. SHALL combinar todos los filtros recibidos con AND y SHALL devolver solo estados `publicado` y `agotado`.

La búsqueda reemplaza la respuesta actual `405 Method Not Allowed` de `GET /eventos`.

#### Scenario: Reemplazar el método GET actualmente no soportado
- **GIVEN** la ruta `GET /eventos`, que antes de este cambio responde `405 Method Not Allowed`
- **WHEN** un usuario autenticado consulta la ruta con parámetros válidos
- **THEN** el servicio procesa la búsqueda y responde `200 OK` en lugar de `405`

#### Scenario: Buscar combinando criterios
- **GIVEN** eventos publicados o agotados con distintos tipos, organizadores, participantes, ciudades y fechas
- **WHEN** un cliente autenticado busca usando más de un criterio en `GET /eventos`
- **THEN** la respuesta incluye solo eventos que cumplen todos los criterios y responde `200 OK`

#### Scenario: Buscar sin criterios
- **GIVEN** eventos en varios estados
- **WHEN** un organizador autenticado consulta `GET /eventos` sin filtros
- **THEN** recibe únicamente eventos `publicado` y `agotado`, ordenados por fecha ascendente y paginados, con `200 OK`

#### Scenario: No mostrar borradores, cancelados ni finalizados en búsqueda
- **GIVEN** hay eventos `borrador`, `cancelado`, `finalizado`, `publicado` y `agotado`
- **WHEN** un administrador busca eventos
- **THEN** los resultados no contienen los tres primeros estados y responden `200 OK`

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
- **WHEN** un organizador realiza la búsqueda
- **THEN** responde `200 OK` con `contenido` vacío y metadatos de paginación, sin error

#### Scenario: Devolver lista vacía si no hay eventos visibles
- **GIVEN** no hay eventos `publicado` ni `agotado`
- **WHEN** un cliente consulta `GET /eventos` sin filtros
- **THEN** responde `200 OK` con `contenido: []`

#### Scenario: Validar identidad y rol para búsqueda
- **GIVEN** una solicitud sin `X-User-Id` o `X-User-Role`, o con rol distinto de `CLIENTE`, `ORGANIZADOR` y `ADMIN`
- **WHEN** consulta `GET /eventos`
- **THEN** responde `401 Unauthorized` si falta identidad o `403 Forbidden` si el rol no es admitido

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
El servicio SHALL ofrecer `GET /eventos/{eventoId}` a clientes, organizadores y administradores autenticados. SHALL devolver datos del evento, lugar y sectores con precio y disponibilidad. La ruta pública SHALL ser independiente de `GET /internal/eventos/{eventoId}`.

#### Scenario: Consultar evento público existente
- **GIVEN** un evento `publicado`, `agotado`, `cancelado` o `finalizado`
- **WHEN** cualquiera de los tres roles admitidos consulta su detalle
- **THEN** responde `200 OK` con nombre, descripción, tipo, `organizadorId`, participantes, fecha y hora, lugar, sectores y precios

#### Scenario: Consultar un borrador propio
- **GIVEN** un evento `borrador` perteneciente al organizador identificado por `X-User-Id`
- **WHEN** ese organizador consulta el detalle
- **THEN** responde `200 OK` con los datos del evento y no consulta disponibilidad a Reservas

#### Scenario: Administrador consulta un borrador
- **GIVEN** un evento `borrador`
- **WHEN** un administrador consulta el detalle
- **THEN** responde `200 OK` con los datos del evento y no consulta disponibilidad a Reservas

#### Scenario: Ocultar borrador a otros usuarios
- **GIVEN** un evento `borrador` que pertenece a otro organizador
- **WHEN** un cliente u otro organizador consulta su detalle
- **THEN** responde `404 Not Found` igual que para un evento inexistente

#### Scenario: Evento inexistente
- **GIVEN** un identificador de evento no registrado
- **WHEN** un usuario autenticado consulta el detalle
- **THEN** responde `404 Not Found` con el formato de error estándar

#### Scenario: Validar identidad y rol para detalle
- **GIVEN** una solicitud sin `X-User-Id` o `X-User-Role`, o con rol distinto de `CLIENTE`, `ORGANIZADOR` y `ADMIN`
- **WHEN** consulta `GET /eventos/{eventoId}`
- **THEN** responde `401 Unauthorized` si falta identidad o `403 Forbidden` si el rol no es admitido

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
