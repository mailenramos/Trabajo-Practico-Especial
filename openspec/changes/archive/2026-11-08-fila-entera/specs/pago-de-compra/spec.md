## ADDED Requirements

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
