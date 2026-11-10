## ADDED Requirements

### Requirement: Reintegro por cancelación de un evento
Para una compra confirmada de un evento cancelado, el sistema SHALL registrar un único
reintegro con un identificador de solicitud propio, que también es la clave de
idempotencia, el motivo "Evento cancelado" y como monto el precio unitario de las entradas
de la compra que seguían vigentes. Un reintento de ese reintegro que termina exitoso SHALL
dejar devueltas esas entradas.

#### Scenario: Un solo reintegro por compra
- **WHEN** la cancelación de un evento se procesa más de una vez
- **THEN** cada compra confirmada tiene un solo reintegro por cancelación

#### Scenario: Reintento exitoso de un reintegro por cancelación
- **WHEN** un administrador reintenta un reintegro por cancelación fallido y Pagos lo confirma
- **THEN** el reintegro queda exitoso con el mismo identificador de solicitud
- **AND** el sistema pide al servicio de entradas pasar a devuelta las entradas incluidas en ese reintegro

#### Scenario: Reintegro exitoso con entradas sin devolver
- **WHEN** Pagos confirma un reintegro por cancelación pero el servicio de entradas no responde al pasar sus entradas a devuelta
- **THEN** el reintegro queda exitoso con el error registrado y el reintento responde que sigue pendiente
- **AND** un reintento posterior no vuelve a pedir el reintegro a Pagos y termina de pasar a devuelta las entradas que faltaban
