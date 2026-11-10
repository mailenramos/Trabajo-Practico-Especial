## ADDED Requirements

### Requirement: Iniciar una compra desde una reserva
El sistema SHALL permitir a un cliente iniciar una compra indicando el identificador
de una reserva vigente que le pertenece. El sistema SHALL consultar los precios de los
sectores al servicio de eventos, calcular el importe total y registrar la compra en
estado pendiente, con el detalle de los lugares y el precio unitario de cada entrada.

#### Scenario: Compra iniciada con una reserva propia vigente
- **WHEN** un cliente inicia la compra indicando una reserva vigente que le pertenece
- **THEN** el sistema consulta los precios de los sectores al servicio de eventos
- **AND** calcula el importe total
- **AND** registra la compra en estado pendiente con el detalle de lugares y el precio unitario de cada entrada

#### Scenario: Reserva de otro cliente
- **WHEN** un cliente intenta comprar una reserva que pertenece a otro cliente
- **THEN** el sistema rechaza la operación
- **AND** no registra ninguna compra

#### Scenario: Reserva inexistente
- **WHEN** un cliente intenta comprar con un identificador de reserva inexistente
- **THEN** el sistema responde con un error de "reserva no encontrada"

### Requirement: Estados de la compra
El sistema SHALL manejar tres estados de compra: pendiente, confirmada y fallida. La
compra SHALL nacer en estado pendiente y SHALL pasar a confirmada o a fallida solo
desde pendiente. Una compra confirmada o fallida SHALL NOT volver a cambiar de estado.

#### Scenario: Compra nueva
- **WHEN** se registra una compra
- **THEN** su estado es pendiente

#### Scenario: Transición no permitida
- **WHEN** se intenta cambiar el estado de una compra que ya está confirmada o fallida
- **THEN** el sistema rechaza el cambio y conserva el estado actual

### Requirement: Verificar la reserva antes de cobrar
El sistema SHALL consultar el estado de la reserva al servicio de disponibilidad y
reservas antes de pedir el cobro. Esta verificación es preventiva: la confirmación de
la venta es la validación definitiva.

#### Scenario: Reserva vigente al momento del cobro
- **WHEN** hay una compra pendiente y se va a solicitar el cobro
- **AND** la reserva sigue vigente
- **THEN** el sistema continúa con el pedido de cobro

#### Scenario: Reserva vencida o liberada
- **WHEN** hay una compra pendiente y se va a solicitar el cobro
- **AND** la reserva está vencida o liberada
- **THEN** el sistema no pide el cobro
- **AND** informa al cliente que debe reservar de nuevo

### Requirement: Solo el titular compra su reserva
El sistema SHALL permitir iniciar una compra únicamente al cliente titular de la
reserva, identificado por el encabezado X-User-Id.

#### Scenario: Cliente distinto del titular
- **WHEN** el valor de X-User-Id no coincide con el titular de la reserva
- **THEN** el sistema rechaza la compra

### Requirement: Una sola compra por reserva
El sistema SHALL registrar como máximo una compra por reserva. Si el mismo cliente repite
el pedido de compra de una reserva cuya compra sigue pendiente, el sistema SHALL devolver
esa compra sin registrar otra. Si la compra de esa reserva ya está confirmada o fallida,
el sistema SHALL rechazar el pedido.

#### Scenario: El cliente repite el pedido con la compra pendiente
- **WHEN** un cliente inicia la compra de una reserva que ya tiene una compra suya pendiente
- **THEN** el sistema devuelve esa misma compra
- **AND** no registra una compra nueva ni vuelve a calcular el total

#### Scenario: La compra de la reserva ya se resolvió
- **WHEN** un cliente inicia la compra de una reserva cuya compra está confirmada o fallida
- **THEN** el sistema rechaza el pedido indicando que la reserva ya tiene una compra

#### Scenario: Dos pedidos simultáneos de la misma reserva
- **WHEN** llegan al mismo tiempo dos pedidos de compra de la misma reserva
- **THEN** el sistema registra una sola compra
- **AND** ambos pedidos reciben esa compra