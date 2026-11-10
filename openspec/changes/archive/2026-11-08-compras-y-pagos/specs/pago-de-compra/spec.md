## ADDED Requirements

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
- **THEN** la compra no queda confirmada
- **AND** el sistema no pide la emisión de entradas
<!-- Pendiente: decidir en este servicio qué pasa con el cobro ya realizado. El servicio de pagos acepta un reintegro común por el total cobrado; se recomienda reintegro automático y compra fallida. -->

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
