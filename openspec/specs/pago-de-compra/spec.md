# pago-de-compra Specification

## Purpose
Cubre la coordinación del cobro de una compra con el servicio de pagos y su resultado: confirmar la venta y emitir las entradas cuando el pago se aprueba, o liberar los lugares cuando se rechaza. También cubre la idempotencia de los reintentos del pago, para que repetirlo nunca cobre ni emita dos veces.

## Requirements

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

### Requirement: Pago rechazado libera los lugares
Cuando el servicio de pagos rechaza el pago, el sistema SHALL registrar la compra como
fallida y SHALL pedir al servicio de disponibilidad y reservas la liberación de los
lugares. El rechazo SHALL interpretarse por el estado del pago devuelto y no solo por el
código de respuesta, porque el servicio de pagos informa un rechazo como una respuesta
normal.

#### Scenario: Pago rechazado
- **WHEN** el servicio de pagos responde que rechazó el pago de una compra pendiente
- **THEN** la compra queda fallida
- **AND** el sistema pide la liberación de los lugares

#### Scenario: Consulta de una compra fallida
- **WHEN** el cliente consulta una compra fallida
- **THEN** figura con ese estado y sin entradas asociadas

### Requirement: Medio de pago válido
El sistema SHALL aceptar únicamente los medios de pago que admite el servicio de pagos:
tarjeta de crédito, tarjeta de débito, transferencia bancaria y billetera digital. El
sistema SHALL rechazar cualquier otro valor antes de pedir el cobro.

#### Scenario: Medio de pago no admitido
- **WHEN** el cliente envía un medio de pago que no es ninguno de los cuatro admitidos
- **THEN** el sistema rechaza el pedido con un error de validación
- **AND** no pide el cobro al servicio de pagos

### Requirement: Una compra con pago aprobado no se cobra de nuevo
El sistema SHALL NOT pedir un segundo cobro de una compra que ya tiene un pago aprobado.

#### Scenario: Compra con pago ya aprobado
- **WHEN** el cliente repite el pago de una compra cuyo pago ya fue aprobado
- **THEN** el sistema no pide un nuevo cobro
- **AND** continúa desde el paso siguiente que falte

### Requirement: Recuperar un cobro de estado incierto
Si el sistema no recibió la respuesta del servicio de pagos, SHALL consultar al servicio
de pagos los pagos de esa compra antes de repetir el cobro y SHALL tener en cuenta solo
un pago aprobado. Si no hay un pago aprobado, SHALL repetir el cobro con la misma clave
de cobro, para que no se cobre dos veces.

#### Scenario: El cobro se hizo pero no llegó la respuesta
- **WHEN** el cliente repite el pago y el sistema no tiene el resultado del cobro anterior
- **AND** el servicio de pagos informa un pago aprobado de esa compra
- **THEN** el sistema usa ese pago y continúa con la confirmación de la venta
- **AND** no pide un nuevo cobro

#### Scenario: No hay un pago aprobado de la compra
- **WHEN** el cliente repite el pago y el sistema no tiene el resultado del cobro anterior
- **AND** el servicio de pagos no informa ningún pago aprobado de esa compra (no hay pagos o solo hay rechazados)
- **THEN** el sistema repite el cobro con la misma clave de cobro

#### Scenario: El cobro anterior había sido rechazado
- **WHEN** el sistema repite el cobro con la misma clave de cobro
- **AND** el intento anterior con esa clave había sido rechazado
- **THEN** el servicio de pagos devuelve el rechazo original
- **AND** la compra queda fallida y se liberan los lugares

### Requirement: Reintento de pago idempotente
El sistema SHALL aceptar una clave de idempotencia por cada operación de pago. La clave
SHALL tener efecto solo para el cliente que la envió. Si la misma operación se repite con
la misma clave, el sistema SHALL devolver el resultado original sin cobrar ni pedir
entradas de nuevo.

#### Scenario: Reintento con la misma clave
- **WHEN** se repite un pedido de pago con la misma clave de idempotencia
- **THEN** el sistema devuelve el resultado original
- **AND** no pide un nuevo cobro ni una nueva emisión de entradas

#### Scenario: Reintento mientras el primer pago está en proceso
- **WHEN** llega un reintento y el primer pago sigue en proceso
- **THEN** el sistema no inicia un segundo cobro
- **AND** informa que la operación está en curso

#### Scenario: Misma clave con datos distintos
- **WHEN** llega una clave ya usada con datos distintos a los del primer pedido
- **THEN** el sistema rechaza el pedido

#### Scenario: Misma clave en dos clientes distintos
- **WHEN** dos clientes distintos envían el mismo valor de clave de idempotencia
- **THEN** el sistema trata cada pedido de forma independiente
- **AND** ninguno recibe el resultado del otro

### Requirement: Retomar un pago interrumpido
Si un pago quedó interrumpido, el sistema SHALL retomarlo desde el último paso hecho
cuando el cliente lo repite con la misma clave. Un pago que sigue en proceso más allá de
un plazo configurable (60 segundos por defecto) SHALL considerarse abandonado y poder
retomarse.

#### Scenario: Reintento de una operación interrumpida
- **WHEN** un pago anterior con la misma clave quedó interrumpido porque otro servicio no respondió o falló la emisión de entradas
- **AND** el cliente repite el pago con la misma clave y los mismos datos
- **THEN** el sistema retoma la operación desde el último paso hecho
- **AND** si hace falta cobrar, usa la misma clave de cobro que el intento anterior

#### Scenario: Operación abandonada por una caída del servicio
- **WHEN** un pago con la misma clave sigue en proceso desde hace más que el plazo configurado
- **AND** el cliente repite el pago con esa clave
- **THEN** el sistema retoma la operación como si estuviera interrumpida

#### Scenario: Reintento antes de cumplirse el plazo
- **WHEN** un pago con la misma clave sigue en proceso y todavía no pasó el plazo configurado
- **AND** el cliente repite el pago con esa clave
- **THEN** el sistema no lo retoma
- **AND** informa que la operación está en curso

### Requirement: Emisión con fila no numérica es una emisión fallida
El sistema SHALL enviar al servicio de entradas la fila de cada lugar como entero y
SHALL leer como entero la fila de cada entrada emitida. Si la respuesta de emisión trae
una fila no numérica, el sistema SHALL tratarla como un error de contrato y contarla como
un intento de emisión fallido, sin asociar entradas a la compra.

#### Scenario: Emisión con filas numéricas
- **WHEN** el pago está aprobado, la venta confirmada y el servicio de entradas devuelve cada entrada con su sector, fila y número enteros
- **THEN** el sistema asocia cada entrada al lugar con el mismo sector, fila y número
- **AND** la compra queda confirmada

#### Scenario: Emisión con una fila no numérica
- **WHEN** el servicio de entradas responde a la emisión con una entrada de fila "A"
- **THEN** el sistema cuenta ese intento como emisión fallida y no asocia entradas
- **AND** si los 3 intentos fallan así, la compra queda pendiente y el sistema responde que la operación no terminó (202)

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

### Requirement: Un pago no reabre una compra cerrada por la cancelación del evento
Si la compra ya no está pendiente porque la cancelación del evento la cerró, un pago nuevo o retomado SHALL NOT cobrar, confirmar la venta ni emitir entradas, y SHALL responder con la compra fallida. El paso a confirmada SHALL hacerse solo si la compra sigue pendiente en ese momento; si el cambio no se aplica, el pago SHALL responder con el estado real de la compra.

#### Scenario: Pago retomado sobre una compra cerrada
- **WHEN** la cancelación del evento cerró una compra pendiente con pago aprobado y emisión fallida
- **AND** el cliente repite el pago con la misma clave
- **THEN** el sistema no pide cobro, confirmación de venta ni emisión
- **AND** responde con la compra fallida y su reintegro por cancelación

#### Scenario: Pago nuevo sobre una compra cerrada sin cobro
- **WHEN** la cancelación cerró una compra pendiente que no tenía cobro
- **AND** el cliente pide el pago con una clave nueva
- **THEN** el sistema no pide el cobro a Pagos
- **AND** responde con la compra fallida

#### Scenario: La compra deja de estar pendiente antes de confirmarla
- **WHEN** al terminar la emisión el cambio de la compra a confirmada no se aplica porque la compra ya no está pendiente
- **THEN** la compra no pasa a confirmada
- **AND** el pago responde con el estado real de la compra

#### Scenario: El pago gana a la cancelación
- **WHEN** el pago retomado deja la compra confirmada antes de que la cancelación la procese
- **THEN** el pago responde con la compra confirmada
- **AND** la cancelación la procesa después como compra confirmada
