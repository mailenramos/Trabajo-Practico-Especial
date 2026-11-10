## MODIFIED Requirements

### Requirement: Devolver automáticamente las compras confirmadas del evento
Al procesar la cancelación, el sistema SHALL pedir al servicio de entradas pasar a en
reintegro las entradas del evento y, por cada compra confirmada, SHALL pedir un reintegro
por el precio unitario de sus entradas vigentes, sin aprobación de un administrador. Las
compras fallidas antes de la cancelación SHALL NOT generar reintegro; las compras
pendientes SHALL cerrarse como lo indica el requisito de compras pendientes del evento.

#### Scenario: Compra confirmada con todas sus entradas vigentes
- **WHEN** se procesa la cancelación de un evento con una compra confirmada de dos entradas vigentes
- **THEN** el sistema pide un reintegro por la suma de sus precios unitarios
- **AND** con el reintegro exitoso las dos entradas pasan a devuelta

#### Scenario: Compra con una entrada ya devuelta
- **WHEN** la compra tiene una entrada con una devolución aprobada y otra vigente
- **THEN** el reintegro por la cancelación es solo por el precio de la entrada vigente

#### Scenario: Compra sin entradas vigentes
- **WHEN** todas las entradas de la compra ya tienen una devolución aprobada
- **THEN** no se pide reintegro por esa compra

#### Scenario: Compras pendientes o fallidas
- **WHEN** se procesa la cancelación y el evento tiene compras que ya estaban fallidas
- **THEN** no se pide ningún reintegro por ellas
- **AND** las compras pendientes se cierran según el requisito de compras pendientes del evento cancelado

#### Scenario: Un reintegro falla y el resto sigue
- **WHEN** el reintegro de una compra falla o no tiene respuesta
- **THEN** queda registrado como fallido o pendiente
- **AND** las demás compras del evento se procesan igual

#### Scenario: La marcación de Entradas no informa entradas nuevas
- **WHEN** al repetirse el procesamiento la marcación del servicio de entradas responde que marcó 0 entradas
- **THEN** el sistema no pide otro reintegro por las compras que ya tienen el suyo

## ADDED Requirements

### Requirement: Cerrar las compras pendientes del evento cancelado
Al procesar la cancelación, el sistema SHALL pasar cada compra pendiente del evento a fallida con un cambio de estado condicional. Solo si ese cambio se aplica y la compra tiene un pago aprobado, SHALL registrar un único reintegro por el total, con motivo "Evento cancelado", origen cancelación de evento y un identificador de solicitud propio guardado antes de pedirlo a Pagos, y SHALL pedirlo.

#### Scenario: Compra pendiente con pago aprobado
- **WHEN** se procesa la cancelación y el evento tiene una compra pendiente con pago aprobado (por ejemplo, porque falló la emisión de entradas)
- **THEN** la compra queda fallida
- **AND** el sistema pide a Pagos un reintegro por el total con motivo "Evento cancelado" y origen cancelación de evento
- **AND** las entradas de la compra que se conocen pasan a devuelta con el reintegro exitoso

#### Scenario: Compra pendiente sin pago registrado pero cobrada
- **WHEN** la compra pendiente no tiene pago registrado y Pagos informa un pago aprobado de esa compra
- **THEN** el sistema guarda ese pago en la compra
- **AND** la compra queda fallida con un reintegro por el total

#### Scenario: Compra pendiente sin cobro
- **WHEN** la compra pendiente no tiene pago registrado y Pagos no informa ningún pago aprobado (no hay pagos o solo rechazados)
- **THEN** la compra queda fallida
- **AND** no se pide ningún reintegro

#### Scenario: Pagos no responde la consulta de pagos
- **WHEN** la compra pendiente no tiene pago registrado y Pagos no responde la consulta de pagos de la compra
- **THEN** la compra sigue pendiente y no se pide reintegro
- **AND** la cancelación queda en proceso para retomarla con un aviso repetido
- **AND** las demás compras del evento se procesan igual

#### Scenario: Reintegro de una compra pendiente que falla
- **WHEN** el reintegro de una compra pendiente cerrada por la cancelación falla o no tiene respuesta
- **THEN** la compra queda fallida igual y el reintegro queda registrado como fallido o pendiente
- **AND** un administrador lo reintenta sobre el mismo reintegro, sin crear otro

#### Scenario: Entradas emitidas cuya respuesta se perdió
- **WHEN** la emisión se hizo en el servicio de entradas pero la respuesta no llegó, y la compra se cierra por la cancelación
- **THEN** el reintegro es por el total de la compra
- **AND** esas entradas quedan en reintegro sin pasar a devuelta, porque el sistema no conoce sus identificadores

### Requirement: El cierre de compras pendientes no se duplica
Un aviso de cancelación repetido o un procesamiento retomado SHALL NOT volver a cerrar una compra ni registrar un segundo reintegro por cancelación para ella. Un reintegro por cancelación de una compra cerrada que quedó pendiente SHALL volver a pedirse con el mismo identificador de solicitud.

#### Scenario: Procesamiento retomado con una compra ya cerrada
- **WHEN** se retoma el procesamiento de una cancelación y una compra pendiente ya se cerró con su reintegro
- **THEN** la compra no se vuelve a cerrar
- **AND** no se registra otro reintegro por ella

#### Scenario: Reintegro sin respuesta al retomar
- **WHEN** se retoma el procesamiento y el reintegro de una compra cerrada sigue pendiente porque Pagos no respondió
- **THEN** el sistema lo vuelve a pedir con el mismo identificador de solicitud y la misma clave
- **AND** Pagos no lo duplica

#### Scenario: Aviso repetido de un evento ya procesado
- **WHEN** llega un aviso repetido de un evento cuya cancelación ya cerró sus compras pendientes
- **THEN** el sistema responde que ya lo procesó (200) sin cerrar compras ni pedir reintegros

### Requirement: Cancelación concurrente con un pago retomado
Si una compra pendiente del evento se está pagando o retomando al mismo tiempo que se procesa la cancelación, SHALL resolverla solo quien cambie primero su estado. Si el pago la confirma primero, la cancelación SHALL tratarla como compra confirmada; si la cancelación la cierra primero, el pago SHALL NOT confirmarla.

#### Scenario: El pago confirma la compra primero
- **WHEN** un pago retomado confirma la compra antes de que la cancelación la procese
- **THEN** la cancelación la trata como compra confirmada
- **AND** pide un único reintegro por el precio de sus entradas vigentes y las pasa a devuelta

#### Scenario: La cancelación cierra la compra primero
- **WHEN** la cancelación cierra la compra antes de que el pago retomado la procese
- **THEN** la compra queda fallida con un único reintegro
- **AND** el pago no cobra de nuevo, no confirma la venta ni emite entradas

#### Scenario: Pago y cancelación a la vez
- **WHEN** un pago retomado y la cancelación procesan la misma compra pendiente al mismo tiempo
- **THEN** la compra termina confirmada con su reintegro por cancelación o fallida con su reintegro, nunca las dos cosas
- **AND** hay exactamente un reintegro por cancelación para esa compra
