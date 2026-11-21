# Delta: eventos

## ADDED Requirements

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

## MODIFIED Requirements

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
