## ADDED Requirements

### Requirement: Lugares numerados con fila entera
El sistema SHALL identificar un lugar numerado por sector, fila y número, donde la fila
y el número son enteros, y un lugar de un sector no numerado sin fila ni número. Si el
servicio de reservas informa un lugar con una fila no numérica, el sistema SHALL tratarlo
como un error de contrato con ese servicio y SHALL NOT registrar la compra.

#### Scenario: Reserva con filas numéricas
- **WHEN** un cliente inicia la compra de una reserva vigente cuyos lugares tienen fila 10 y números 1 y 2
- **THEN** el sistema registra la compra con un lugar por entrada
- **AND** cada lugar muestra la fila como el número 10

#### Scenario: Sector no numerado
- **WHEN** un cliente inicia la compra de una reserva con unidades de un sector no numerado
- **THEN** esos lugares se muestran sin fila ni número

#### Scenario: Reserva con una fila no numérica
- **WHEN** un cliente inicia la compra y el servicio de reservas informa un lugar con fila "A"
- **THEN** el sistema rechaza el pedido indicando un error de contrato con el servicio de reservas
- **AND** no registra ninguna compra
