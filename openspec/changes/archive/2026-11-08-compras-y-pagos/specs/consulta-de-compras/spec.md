## ADDED Requirements

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