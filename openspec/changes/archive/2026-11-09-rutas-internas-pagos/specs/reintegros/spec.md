## ADDED Requirements

### Requirement: Reintegros por las rutas internas de Pagos

El servicio SHALL pedir, reintentar y consultar los reintegros por las rutas internas de Pagos (`POST /internal/pagos/{paymentId}/reintegros`, `POST /internal/reintegros/{refundId}/reintentar` y `GET /internal/reintegros/{refundId}`), y SHALL NOT llamar a las rutas `/api/v1/...`. Las tres rutas SHALL ser configurables, con las internas como valor por defecto.

#### Scenario: Pedido de reintegro por la ruta interna
- **WHEN** el servicio pide un reintegro (por una devolución, una cancelación o una venta rechazada)
- **THEN** llama a `POST /internal/pagos/{paymentId}/reintegros` con su clave en `Idempotency-Key` y el mismo cuerpo de siempre (`refundRequestId`, `amount`, `reason`)

#### Scenario: Repetición de un reintegro pendiente
- **WHEN** se reintenta un reintegro que quedó pendiente sin respuesta de Pagos
- **THEN** el servicio repite `POST /internal/pagos/{paymentId}/reintegros` con el mismo `refundRequestId` y la misma `Idempotency-Key`
- **AND** Pagos no crea otro reintegro

#### Scenario: Reintento de un reintegro fallido por la ruta interna
- **WHEN** un administrador reintenta un reintegro fallido
- **THEN** el servicio llama a `POST /internal/reintegros/{refundId}/reintentar`, sin cuerpo ni clave, sobre el mismo reintegro de Pagos, sin crear uno nuevo

#### Scenario: Reintento rechazado por conflicto
- **WHEN** `POST /internal/reintegros/{refundId}/reintentar` responde 409
- **THEN** el servicio consulta el reintegro con `GET /internal/reintegros/{refundId}` y registra el estado que informa Pagos

#### Scenario: Pagos no informa el reintegro o no responde
- **WHEN** tras el 409 del reintento `GET /internal/reintegros/{refundId}` responde 404, o cualquiera de las tres rutas responde un error 5xx o no responde
- **THEN** el reintegro conserva su estado, se registra el error y queda para reintentar, sin crear otro reintegro

#### Scenario: Rutas configuradas
- **WHEN** las propiedades de las rutas de reintegro, reintento y consulta de reintegro tienen otro valor
- **THEN** el servicio llama a Pagos por esas rutas
