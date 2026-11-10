## ADDED Requirements

### Requirement: La compra muestra sus reintegros
Al consultar una compra propia, el sistema SHALL mostrar los reintegros pedidos sobre esa
compra, con su monto, su origen y su estado (pendiente, exitoso o fallido).

#### Scenario: Compra con una entrada devuelta
- **WHEN** un cliente consulta una compra con una devolución aprobada
- **THEN** ve el reintegro de esa entrada con su monto y su estado

#### Scenario: Compra fallida por venta rechazada
- **WHEN** un cliente consulta una compra que quedó fallida porque la venta se rechazó después del cobro
- **THEN** ve el reintegro automático por el total con su estado

#### Scenario: Compra sin reintegros
- **WHEN** un cliente consulta una compra sin reintegros
- **THEN** la compra se muestra sin reintegros
