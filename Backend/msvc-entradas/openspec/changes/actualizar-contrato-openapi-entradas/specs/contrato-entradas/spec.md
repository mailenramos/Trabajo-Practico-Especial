# Delta de especificación: contrato de entradas

## MODIFIED Requirements

### Requirement: Representación de entradas

La API DEBE serializar una entrada detallada con las propiedades `idEntrada`, `codigoQr`,
`estado`, `evento`, `sector`, `fila`, `numero`, `idCompra` e `idCliente`.

Los identificadores de evento, sector y cliente DEBEN exponerse como cadenas de texto. Los
identificadores de entrada, compra y código QR DEBEN exponerse como UUID.

#### Scenario: Consulta de una entrada numerada

- **WHEN** un cliente consulta una entrada asociada a una fila y un número
- **THEN** la respuesta contiene `idEntrada`, `fila` y `numero`
- **AND** no contiene `id` ni `idAsiento`

### Requirement: Emisión de entradas

La solicitud de emisión DEBE describir cada lugar mediante `idSector`, `nombreSector`, `fila`
y `numero`. `fila` y `numero` pueden ser nulos para sectores no numerados. Si `numero` tiene
valor, `fila` DEBE tener valor y cada combinación `(idSector, fila, numero)` DEBE ser única en
la solicitud.

La respuesta de emisión DEBE identificar cada elemento con `idEntrada`.

#### Scenario: Emisión para sector no numerado

- **WHEN** se emiten lugares de un sector no numerado
- **THEN** cada lugar usa `fila: null` y `numero: null`

### Requirement: Formato de error

Todo error manejado por la API DEBE incluir `timestamp`, `status`, `error`, `codigo` y
`mensaje`; puede incluir `campos` como objeto cuyas claves son nombres de campos y cuyos valores
son mensajes de validación.

#### Scenario: Validación fallida del cuerpo de emisión

- **WHEN** la solicitud no cumple una restricción de validación
- **THEN** la API responde `400`
- **AND** `codigo` es `VALIDACION`
- **AND** `campos` contiene los mensajes asociados a los campos inválidos

### Requirement: Identificación de endpoints públicos

Los endpoints públicos de entradas DEBEN requerir el encabezado `X-User-Id`. El contrato del
microservicio no DEBE declarar `X-User-Role` como requisito, porque no es consumido por el
controlador.

#### Scenario: Ausencia de identificación

- **WHEN** se invoca un endpoint público sin `X-User-Id`
- **THEN** la API responde `401` con `codigo` `FALTA_IDENTIFICACION`
