## MODIFIED Requirements

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

## ADDED Requirements

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
