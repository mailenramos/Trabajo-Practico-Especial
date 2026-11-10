# consulta-de-compras Specification

## Purpose
Cubre el listado y el detalle de las compras del cliente, con acceso solo a las propias.

## Requirements

### Requirement: Consultar las compras propias
El sistema SHALL permitir a un cliente consultar sus compras, mostrando evento,
sector, lugares adquiridos, precio unitario de cada entrada, total, medio de pago,
fecha y estado (pendiente, confirmada o fallida). El sistema SHALL mostrar los precios
vigentes al momento de la compra.

#### Scenario: Cliente con compras
- **WHEN** un cliente consulta sus compras
- **THEN** ve solo las suyas
- **AND** cada una muestra evento, sector, lugares, precio unitario, total, medio de pago, fecha y estado

#### Scenario: Precios históricos
- **WHEN** se consulta una compra ya registrada
- **THEN** los precios mostrados son los vigentes al momento de la compra

### Requirement: Aislamiento entre clientes
El sistema SHALL denegar el acceso a una compra que pertenece a otro cliente.

#### Scenario: Compra de otro cliente
- **WHEN** un cliente intenta consultar una compra de otro cliente
- **THEN** el sistema le deniega el acceso

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
