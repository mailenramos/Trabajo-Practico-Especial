# reintegros Specification

## Purpose
Cubre el registro de cada reintegro que este servicio pide al servicio de pagos, su
estado (pendiente, exitoso o fallido) y su reintento sin devolver dos veces el mismo
dinero.

## Requirements

### Requirement: Registrar cada reintegro
El sistema SHALL registrar cada reintegro antes de pedirlo al servicio de pagos, con un
identificador de solicitud único, el monto, un motivo no vacío y su origen. El reintegro
SHALL nacer pendiente y SHALL pasar a exitoso o fallido según la respuesta de Pagos.

#### Scenario: Reintegro exitoso
- **WHEN** el sistema pide un reintegro y el servicio de pagos lo confirma
- **THEN** el reintegro queda exitoso con el identificador que asignó Pagos

#### Scenario: Reintegro fallido
- **WHEN** el sistema pide un reintegro y el servicio de pagos no lo logra
- **THEN** el reintegro queda fallido con el identificador que asignó Pagos

#### Scenario: Sin respuesta de Pagos
- **WHEN** el sistema pide un reintegro y no recibe respuesta
- **THEN** el reintegro queda pendiente
- **AND** se puede reintentar

#### Scenario: Motivo obligatorio
- **WHEN** se va a pedir un reintegro
- **THEN** el pedido siempre lleva un motivo no vacío, porque Pagos rechaza uno sin motivo

### Requirement: Reintegro de una devolución
Para una devolución aprobada, el sistema SHALL usar el identificador de la devolución como
identificador de la solicitud de reintegro y como clave de idempotencia, SHALL pedir el
precio unitario de la entrada sobre el pago de la compra, y SHALL admitir varios
reintegros de una misma compra, uno por entrada devuelta.

#### Scenario: Reintegro por el precio de la entrada
- **WHEN** se pide el reintegro de una devolución aprobada
- **THEN** el monto es el precio unitario pagado por esa entrada
- **AND** el identificador de la solicitud de reintegro es el identificador de la devolución

#### Scenario: Varias entradas de la misma compra
- **WHEN** se aprueban por separado las devoluciones de dos entradas de la misma compra
- **THEN** cada una genera su propio reintegro por su precio unitario

### Requirement: Reintentar un reintegro sin duplicarlo
El sistema SHALL permitir a un administrador reintentar un reintegro pendiente o fallido.
Un reintegro fallido SHALL reintentarse con el reintento de Pagos sobre el mismo
reintegro, nunca con un identificador de solicitud nuevo. Un reintegro pendiente sin
respuesta SHALL volver a pedirse con el mismo identificador y la misma clave.

#### Scenario: Reintento de un reintegro fallido
- **WHEN** un administrador reintenta un reintegro fallido
- **THEN** el sistema usa el reintento de Pagos sobre ese mismo reintegro
- **AND** no crea un identificador de solicitud nuevo

#### Scenario: Reintento de un reintegro sin respuesta
- **WHEN** un administrador reintenta un reintegro pendiente que nunca recibió respuesta
- **THEN** el sistema vuelve a pedirlo con el mismo identificador y la misma clave
- **AND** Pagos no lo duplica

#### Scenario: Pagos rechaza el reintento
- **WHEN** el servicio de pagos rechaza el reintento porque el reintegro ya no está fallido
- **THEN** el sistema consulta el estado del reintegro en Pagos y registra ese estado

#### Scenario: Reintegro ya exitoso
- **WHEN** un administrador reintenta un reintegro exitoso
- **THEN** el sistema no pide nada a Pagos y devuelve el reintegro como está

#### Scenario: Reintento de una devolución
- **WHEN** el reintento deja exitoso el reintegro de una devolución
- **THEN** el sistema pide al servicio de entradas pasar la entrada a devuelta

#### Scenario: Usuario que no es administrador
- **WHEN** un usuario sin rol de administrador intenta reintentar un reintegro
- **THEN** el sistema le deniega el acceso

### Requirement: Consultar los reintegros para resolverlos
El sistema SHALL permitir a un administrador listar los reintegros, filtrados por estado,
para encontrar los pendientes y fallidos que hay que reintentar.

#### Scenario: Reintegros fallidos
- **WHEN** un administrador lista los reintegros en estado fallido
- **THEN** ve todos los reintegros fallidos con su monto, origen, intentos y último error

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

### Requirement: Entrada a devuelta rechazada por transición inválida en un reintegro por cancelación
Cuando el reintegro de un evento cancelado queda exitoso y Entradas responde 409 `TRANSICION_INVALIDA` al pasar una de sus entradas a devuelta, el sistema SHALL mantener el reintegro exitoso, SHALL registrar en él un error de conflicto de estado de la entrada, distinguible de una falla del servicio, y SHALL permitir reintentarlo sin pedir otro reintegro a Pagos.

#### Scenario: Conflicto de estado al pasar a devuelta
- **WHEN** el reintegro de un evento cancelado queda exitoso y Entradas responde 409 `TRANSICION_INVALIDA` para una de sus entradas
- **THEN** el reintegro sigue exitoso
- **AND** las entradas ya devueltas quedan marcadas como devueltas
- **AND** el error registrado indica un conflicto de estado de la entrada y no que Entradas no está disponible

#### Scenario: Reintento tras el conflicto
- **WHEN** un administrador reintenta ese reintegro y Entradas ahora responde 200 al pasar la entrada a devuelta
- **THEN** el sistema no pide otro reintegro a Pagos
- **AND** la entrada queda devuelta y se limpia el error

#### Scenario: Entradas no responde al pasar a devuelta
- **WHEN** el reintegro de un evento cancelado queda exitoso y Entradas no responde al pasar una entrada a devuelta
- **THEN** el error registrado indica que el servicio de entradas no está disponible
