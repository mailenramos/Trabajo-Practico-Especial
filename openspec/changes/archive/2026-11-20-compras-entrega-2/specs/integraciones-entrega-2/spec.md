## ADDED Requirements

### Requirement: Autorización de servicio en endpoints internos
Los endpoints `/internal/**` nuevos de este servicio SHALL exigir el encabezado `X-Service-Token` y compararlo en tiempo constante con el secreto configurado por variable de entorno.

#### Scenario: Token correcto
- **WHEN** la solicitud trae el token configurado
- **THEN** el servicio procesa la consulta

#### Scenario: Token ausente o incorrecto
- **WHEN** falta el encabezado o su valor no coincide
- **THEN** responde 403 con `{timestamp, status, error, mensaje}` y el código `SERVICIO_NO_AUTORIZADO`

#### Scenario: Secreto no configurado
- **WHEN** la variable de entorno del secreto no está definida
- **THEN** el servicio rechaza todas las llamadas internas con 403

### Requirement: Co-compras de un evento
El servicio SHALL exponer `GET /internal/eventos/{eventoId}/co-compras`, que devuelve los otros eventos comprados por clientes con una compra CONFIRMADA del evento indicado, con la cantidad de clientes en común, ordenados de mayor a menor cantidad.

#### Scenario: Evento con co-compras
- **WHEN** hay clientes con compra confirmada del evento A que también compraron los eventos B y C
- **THEN** responde 200 con `{eventoId, coCompras: [{eventoId, cantidad}]}` ordenado por cantidad descendente y sin incluir al evento A

#### Scenario: Límite
- **WHEN** se pide `limite=3` o no se pide límite
- **THEN** devuelve como máximo 3 eventos, o 10 por defecto, y un límite mayor a 20 se trata como 20

#### Scenario: Sin resultados
- **WHEN** el evento no tiene compras confirmadas o ningún cliente compró otro evento
- **THEN** responde 200 con `coCompras: []`, no 404

#### Scenario: Compras no confirmadas
- **WHEN** una compra está PENDIENTE o FALLIDA
- **THEN** no se cuenta para nada

#### Scenario: Cliente repetido
- **WHEN** un cliente tiene varias compras confirmadas del mismo evento
- **THEN** cuenta una sola vez en la cantidad

### Requirement: Tipo de evento en la compra
El servicio SHALL guardar `tipoEvento` en la compra, tomado del evento que devuelve Eventos al iniciarla.

#### Scenario: Compra nueva
- **WHEN** se inicia una compra y Eventos devuelve `tipo`
- **THEN** la compra guarda ese tipo

#### Scenario: Eventos sin tipo
- **WHEN** Eventos no devuelve `tipo`
- **THEN** la compra se crea igual con `tipoEvento` nulo

### Requirement: Historial de preferencias
El servicio SHALL exponer `GET /internal/usuarios/{usuarioId}/historial-preferencias`, con los tipos de evento de las compras CONFIRMADAS del cliente, sin repetir y con la cantidad de compras de cada tipo, de mayor a menor, y `eventosComprados`: los `eventoId` distintos de esas compras, del más reciente al más antiguo, máximo 20.

#### Scenario: Cliente con compras
- **WHEN** el cliente tiene 3 compras confirmadas de RECITAL y 1 de TEATRO
- **THEN** responde 200 con `{usuarioId, tipos: [{tipo: "RECITAL", cantidad: 3}, {tipo: "TEATRO", cantidad: 1}]}`

#### Scenario: Sin compras
- **WHEN** el cliente no tiene compras confirmadas
- **THEN** responde 200 con `tipos: []` y `eventosComprados: []`, no 404

#### Scenario: Eventos comprados
- **WHEN** el cliente tiene compras confirmadas de los eventos A (la más reciente), B y A otra vez
- **THEN** `eventosComprados` es `[A, B]`, sin repetir y del más reciente al más antiguo

#### Scenario: Límite de eventos comprados
- **WHEN** el cliente compró más de 20 eventos distintos
- **THEN** `eventosComprados` devuelve solo los 20 más recientes

#### Scenario: Compras confirmadas sin tipo
- **WHEN** una compra confirmada tiene `tipoEvento` nulo
- **THEN** no cuenta en `tipos`, pero su `eventoId` sí figura en `eventosComprados`


### Requirement: Modificación de eventos
El servicio SHALL exponer `POST /internal/eventos-modificados` (con `X-Service-Token`, sin pasar por el Gateway). El cuerpo SHALL traer `eventoId` y `version` (entero >= 1) y, como mínimo, uno de `fechaHora` (ISO-8601 UTC), `lugar` o `ciudad`. El servicio SHALL deduplicar por `eventoId` + `version`.

#### Scenario: Modificación nueva con fecha
- **WHEN** llega una modificación con una versión que no se procesó y trae `fechaHora`
- **THEN** actualiza `eventoFechaInicio` en las compras y solicitudes de devolución del evento, genera un aviso EVENTO_MODIFICADO por cada cliente con compra confirmada y responde 202 con `{eventoId, version, resultado}`

#### Scenario: Solo cambia el lugar
- **WHEN** la modificación trae `lugar` o `ciudad` pero no `fechaHora`
- **THEN** no cambia ninguna fecha guardada, y los avisos igual se generan con el lugar nuevo

#### Scenario: Reenvío de la misma versión (idempotencia)
- **WHEN** llega otra vez el mismo `eventoId` y `version`
- **THEN** no se actualiza nada, no se crean avisos nuevos y responde 200

#### Scenario: Versión anterior
- **WHEN** llega una versión menor que la ya procesada del mismo evento
- **THEN** se ignora, sin tocar fechas ni crear avisos, y responde 200

#### Scenario: Compras no confirmadas
- **WHEN** hay compras PENDIENTES o FALLIDAS del evento
- **THEN** no reciben aviso, y un cliente con varias compras confirmadas recibe un solo aviso

#### Scenario: Evento sin compras
- **WHEN** el evento no tiene compras
- **THEN** registra la modificación y responde 202 sin generar avisos

#### Scenario: Cuerpo inválido
- **WHEN** falta `eventoId` o `version`, o no viene ninguno de `fechaHora`, `lugar` o `ciudad`, o `fechaHora` no es una fecha válida
- **THEN** responde 400 con `{timestamp, status, error, mensaje, campos}` y no guarda nada

#### Scenario: Token incorrecto
- **WHEN** falta el token o no coincide
- **THEN** responde 403 y no guarda nada

### Requirement: Avisos a Notificaciones mediante outbox
El servicio SHALL guardar un aviso pendiente en la misma transacción que cambia el estado de una compra, devolución o reintegro, y una tarea programada SHALL enviarlo a `POST /internal/notificaciones` con `X-Service-Token`. Los tipos son COMPRA_CONFIRMADA, ENTRADAS_DISPONIBLES, DEVOLUCION_APROBADA, DEVOLUCION_RECHAZADA, RESULTADO_REINTEGRO, EVENTO_CANCELADO y EVENTO_MODIFICADO (los dos últimos, uno por cada comprador afectado). Cada aviso SHALL tener un `acontecimientoId` estable.

#### Scenario: Aviso guardado con la operación
- **WHEN** una compra pasa a CONFIRMADA
- **THEN** en la misma transacción se guarda un aviso PENDIENTE de tipo COMPRA_CONFIRMADA para el cliente

#### Scenario: Un aviso por comprador del mismo acontecimiento
- **WHEN** se cancela o modifica un evento con varios compradores
- **THEN** se guarda un aviso por comprador con el mismo `acontecimientoId`, y registrar dos veces el mismo comprador no crea un segundo aviso

#### Scenario: Falla la operación
- **WHEN** la transacción de negocio se revierte
- **THEN** no queda ningún aviso guardado

#### Scenario: Envío exitoso
- **WHEN** la tarea envía el aviso y Notificaciones responde 2xx
- **THEN** el aviso queda ENVIADO

#### Scenario: Reintento (idempotencia)
- **WHEN** el envío falla por un error temporal o se repite
- **THEN** se reintenta con el mismo `acontecimientoId` y Notificaciones no duplica la notificación

#### Scenario: Intentos agotados
- **WHEN** se alcanza el máximo de intentos
- **THEN** el aviso queda en ERROR_FINAL con el último error, y la compra o devolución no se modifica

#### Scenario: Rechazo definitivo de Notificaciones
- **WHEN** Notificaciones responde 400, 415 o 422 al aviso
- **THEN** el aviso queda en ERROR_FINAL sin más reintentos, con el error recibido

#### Scenario: Token rechazado por Notificaciones
- **WHEN** Notificaciones responde 403
- **THEN** se trata como falla temporal y el aviso se reintenta con espera creciente hasta el máximo de intentos

#### Scenario: Aviso repetido dentro de la operación
- **WHEN** una operación de negocio registra un aviso que ya existe con el mismo acontecimiento, tipo y destinatario
- **THEN** no se crea un segundo aviso y la operación se confirma igual, sin revertirse

#### Scenario: Envío interrumpido
- **WHEN** un aviso quedó PROCESANDO más allá de su vencimiento
- **THEN** vuelve a PENDIENTE contando el intento, o pasa a ERROR_FINAL si ya agotó los intentos, y el resultado tardío de ese envío no pisa su estado

#### Scenario: Solicitudes cerradas por cancelación del evento
- **WHEN** la cancelación de un evento cierra como rechazadas las solicitudes de devolución pendientes
- **THEN** no se genera DEVOLUCION_RECHAZADA; el cliente recibe EVENTO_CANCELADO

#### Scenario: Notificaciones caído
- **WHEN** Notificaciones no responde
- **THEN** la compra, la devolución y el reintegro se completan igual
