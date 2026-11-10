## ADDED Requirements

### Requirement: Cobro y consulta de pagos por las rutas internas de Pagos

El servicio SHALL pedir los cobros a Pagos con `POST /internal/pagos`, con el encabezado `Idempotency-Key`, y SHALL consultar los pagos de una compra con `GET /internal/pagos/compra/{purchaseId}`, que responde una lista. El servicio SHALL NOT llamar a las rutas `/api/v1/payments`. Las dos rutas SHALL ser configurables, con las rutas internas como valor por defecto; los cuerpos, los códigos de respuesta y el formato de error no cambian.

#### Scenario: Cobro por la ruta interna
- **WHEN** un cliente paga una compra pendiente
- **THEN** el servicio llama a `POST /internal/pagos` con la clave de cobro en `Idempotency-Key` y el cuerpo `purchaseId`, `amount`, `paymentMethod`
- **AND** la respuesta del cobro se interpreta igual que antes (aprobado confirma, rechazado libera)

#### Scenario: Reintento de cobro con la misma clave
- **WHEN** se repite el cobro de una compra con la misma clave de cobro (por ejemplo, porque se perdió la respuesta)
- **THEN** el servicio vuelve a llamar a `POST /internal/pagos` con la misma `Idempotency-Key` y el mismo cuerpo
- **AND** no se cobra dos veces

#### Scenario: Consulta de pagos por compra sin pagos
- **WHEN** el servicio consulta los pagos de una compra y Pagos responde 200 con `[]` en `GET /internal/pagos/compra/{purchaseId}`
- **THEN** el servicio entiende que la compra no tiene pagos

#### Scenario: Conflicto o falla de Pagos en la ruta interna
- **WHEN** `POST /internal/pagos` responde 409, o cualquiera de las dos rutas responde un error 5xx o no responde
- **THEN** el servicio lo trata como hasta ahora: el 409 como conflicto de cobro y el resto como falla de Pagos (503), sin dejar el pago a medias

#### Scenario: Rutas configuradas
- **WHEN** las propiedades de las rutas de cobro y de pagos por compra tienen otro valor
- **THEN** el servicio llama a Pagos por esas rutas
