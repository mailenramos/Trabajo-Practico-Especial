## MODIFIED Requirements

### Requirement: Pago aprobado confirma la venta y emite las entradas
Cuando el servicio de pagos aprueba el pago de una compra pendiente, el sistema SHALL
guardar el identificador del pago en la compra, pedir la confirmación de la venta al
servicio de disponibilidad y reservas y, una vez confirmada, SHALL pedir al servicio de
entradas la emisión de una entrada por cada lugar comprado. Entonces SHALL dejar la
compra en estado confirmada.

#### Scenario: Pago aprobado con reserva vigente
- **WHEN** el servicio de pagos aprueba el pago de una compra pendiente con la reserva vigente
- **THEN** el sistema guarda el identificador del pago en la compra
- **AND** pide la confirmación de la venta al servicio de disponibilidad y reservas
- **AND** la compra queda confirmada

#### Scenario: Emisión de entradas
- **WHEN** la venta queda confirmada
- **THEN** el sistema pide al servicio de entradas emitir una entrada por cada lugar comprado
- **AND** cada entrada tiene un código QR único

#### Scenario: Confirmación de venta rechazada
- **WHEN** el servicio de disponibilidad y reservas rechaza la confirmación porque la reserva venció o se liberó
- **THEN** la compra queda fallida
- **AND** el sistema no pide la emisión de entradas
- **AND** el sistema pide al servicio de pagos un reintegro automático por el total cobrado, con un identificador de solicitud propio

#### Scenario: Reintegro automático sin respuesta o fallido
- **WHEN** la venta fue rechazada y el reintegro automático falla o no recibe respuesta
- **THEN** la compra queda fallida igual
- **AND** el reintegro queda registrado como fallido o pendiente para reintentarlo

#### Scenario: Repetir el pago tras una venta rechazada
- **WHEN** el cliente repite el pago de una compra que quedó fallida por una venta rechazada
- **THEN** el sistema no vuelve a cobrar ni pide un segundo reintegro automático

#### Scenario: Emisión de entradas fallida
- **WHEN** el pago está aprobado y la venta confirmada
- **AND** el servicio de entradas falla al emitir tras 3 intentos
- **THEN** la compra queda pendiente
- **AND** no se vuelve a cobrar
- **AND** el sistema responde que la operación no terminó (202) con la compra pendiente y sin entradas

#### Scenario: Repetir el pago tras una emisión fallida
- **WHEN** el cliente repite el pago con la misma clave de idempotencia
- **AND** el pago ya estaba aprobado y la venta confirmada
- **THEN** el sistema solo repite la emisión de entradas
- **AND** no vuelve a pedir el cobro ni la confirmación de la venta
- **AND** al emitirlas, la compra queda confirmada
