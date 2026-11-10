## ADDED Requirements

### Requirement: Un pago no reabre una compra cerrada por la cancelación del evento
Si la compra ya no está pendiente porque la cancelación del evento la cerró, un pago nuevo o retomado SHALL NOT cobrar, confirmar la venta ni emitir entradas, y SHALL responder con la compra fallida. El paso a confirmada SHALL hacerse solo si la compra sigue pendiente en ese momento; si el cambio no se aplica, el pago SHALL responder con el estado real de la compra.

#### Scenario: Pago retomado sobre una compra cerrada
- **WHEN** la cancelación del evento cerró una compra pendiente con pago aprobado y emisión fallida
- **AND** el cliente repite el pago con la misma clave
- **THEN** el sistema no pide cobro, confirmación de venta ni emisión
- **AND** responde con la compra fallida y su reintegro por cancelación

#### Scenario: Pago nuevo sobre una compra cerrada sin cobro
- **WHEN** la cancelación cerró una compra pendiente que no tenía cobro
- **AND** el cliente pide el pago con una clave nueva
- **THEN** el sistema no pide el cobro a Pagos
- **AND** responde con la compra fallida

#### Scenario: La compra deja de estar pendiente antes de confirmarla
- **WHEN** al terminar la emisión el cambio de la compra a confirmada no se aplica porque la compra ya no está pendiente
- **THEN** la compra no pasa a confirmada
- **AND** el pago responde con el estado real de la compra

#### Scenario: El pago gana a la cancelación
- **WHEN** el pago retomado deja la compra confirmada antes de que la cancelación la procese
- **THEN** el pago responde con la compra confirmada
- **AND** la cancelación la procesa después como compra confirmada
