## ADDED Requirements

### Requirement: Pedido de emisión con el contrato confirmado de Entradas
El sistema SHALL pedir la emisión con `idCompra`, `idCliente`, `evento` (`id`, `nombre`, `comienzaEn`) y `lugares`, una línea por lugar comprado con `idSector`, `nombreSector`, `fila` y `numero` enteros. En un sector no numerado SHALL enviar una línea por lugar con `fila` y `numero` en null. El pedido SHALL ser el mismo en cada reintento, para que Entradas lo reconozca como repetición por `idCompra`.

#### Scenario: Emisión de lugares numerados
- **WHEN** el sistema pide la emisión de una compra con el lugar fila 10, número 1 del sector Platea
- **THEN** el pedido lleva en `lugares` una línea con `idSector`, `nombreSector` "Platea", `fila` 10 y `numero` 1 como enteros
- **AND** no lleva los campos `lineas`, `sectorId` ni `sectorNombre`

#### Scenario: Emisión de un sector no numerado
- **WHEN** el sistema pide la emisión de una compra con dos lugares de un sector no numerado
- **THEN** el pedido lleva dos líneas de ese sector con `fila` y `numero` en null
- **AND** el sistema asocia a cada lugar una de las entradas devueltas con `fila` y `numero` en null

#### Scenario: Reintento de la emisión
- **WHEN** la emisión falla y el sistema la repite con la misma compra
- **THEN** el pedido repetido es idéntico al primero
- **AND** Entradas devuelve las mismas entradas sin emitirlas de nuevo

#### Scenario: Entradas no responde a la emisión
- **WHEN** el servicio de entradas no responde a los 3 intentos de emisión
- **THEN** la compra queda pendiente sin entradas asociadas y no se vuelve a cobrar
