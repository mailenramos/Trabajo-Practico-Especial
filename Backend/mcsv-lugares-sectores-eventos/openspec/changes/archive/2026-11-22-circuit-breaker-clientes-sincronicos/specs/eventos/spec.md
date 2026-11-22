# Spec Delta

## MODIFIED Requirements

### Requirement: Incluir disponibilidad de Reservas en el detalle público
Para eventos `publicado` o `agotado`, el servicio SHALL consultar `GET /disponibilidad/eventos/{eventoId}` en Reservas y combinar sus resultados con los sectores mediante `sectorId`, salvo cuando el circuito rechace la llamada. Para otros estados no SHALL consultar a Reservas. Si Reservas falla o el circuito rechaza la llamada, el detalle SHALL responder `200 OK` con disponibilidad `null` en los sectores afectados.

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

#### Scenario: Circuito de Reservas abierto
- **GIVEN** un evento `publicado` o `agotado` y el circuito de Reservas abierto
- **WHEN** se consulta el detalle
- **THEN** responde `200 OK` con disponibilidad `null` en todos los sectores sin llamar a Reservas

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
