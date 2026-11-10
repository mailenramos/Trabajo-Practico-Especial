## ADDED Requirements

### Requirement: Consulta de la entrada según el contrato confirmado
El sistema SHALL leer la entrada de `GET /internal/entradas/{id}` con `idEntrada`, `codigoQr`, `estado`, `evento` (`id`, `nombre`, `comienzaEn`), el sector anidado (`sector.id`, `sector.nombre`), `fila`, `numero`, `idCompra` e `idCliente`. SHALL tomar el sector de `sector.id` y `sector.nombre`. Un 404 con código `ENTRADA_NO_ENCONTRADA` SHALL significar que la entrada no existe; cualquier otra falla SHALL tratarse como falla del servicio.

#### Scenario: Entrada vigente propia
- **WHEN** Entradas responde la entrada con `sector: {id: "platea", nombre: "Platea"}`, `idCliente` del solicitante, `estado` VIGENTE y el evento a más de 72 horas
- **THEN** el sistema registra la solicitud pendiente
- **AND** toma el sector de `sector.id` y `sector.nombre`

#### Scenario: Entrada inexistente en Entradas
- **WHEN** Entradas responde 404 con el código `ENTRADA_NO_ENCONTRADA`
- **THEN** el sistema responde que la entrada no existe

#### Scenario: 404 sin el código de entrada inexistente
- **WHEN** Entradas responde 404 sin el código `ENTRADA_NO_ENCONTRADA`
- **THEN** el sistema lo trata como una falla del servicio de entradas y no como una entrada inexistente

### Requirement: Código de error de Entradas en error o en codigo
El sistema SHALL tomar el código de negocio de una respuesta de error de Entradas del campo `error` y, si no viene, del campo `codigo`.

#### Scenario: Código en el formato del grupo
- **WHEN** Entradas responde 404 con `{"error": "ENTRADA_NO_ENCONTRADA"}`
- **THEN** el sistema responde que la entrada no existe

#### Scenario: Código en el formato anterior
- **WHEN** Entradas responde 404 con `{"codigo": "ENTRADA_NO_ENCONTRADA"}` y sin `error`
- **THEN** el sistema responde que la entrada no existe

### Requirement: Transición inválida en Entradas es un conflicto de estado
Cuando Entradas responde 409 `TRANSICION_INVALIDA` al pasar una entrada a en reintegro o a devuelta, el sistema SHALL registrar el paso como incompleto por conflicto de estado, distinguible de una falla del servicio, y SHALL NOT revertir los pasos hechos. Un 200, también al repetir el pedido, SHALL ser éxito.

#### Scenario: Paso a en reintegro rechazado por transición inválida
- **WHEN** un administrador aprueba una solicitud y Entradas responde 409 `TRANSICION_INVALIDA` al pasar la entrada a en reintegro
- **THEN** la solicitud queda aprobada con la coordinación incompleta en el paso de la entrada a en reintegro
- **AND** el error registrado indica un conflicto de estado de la entrada y no que Entradas no está disponible
- **AND** no se libera el lugar ni se pide el reintegro

#### Scenario: Paso a devuelta rechazado por transición inválida
- **WHEN** el reintegro de una devolución queda exitoso y Entradas responde 409 `TRANSICION_INVALIDA` al pasar la entrada a devuelta
- **THEN** la solicitud queda aprobada con la coordinación incompleta en el paso de la entrada a devuelta
- **AND** el reintegro sigue exitoso
- **AND** el error registrado indica un conflicto de estado de la entrada

#### Scenario: Entradas no responde al cambio de estado
- **WHEN** un administrador aprueba una solicitud y Entradas no responde al pasar la entrada a en reintegro
- **THEN** el error registrado indica que el servicio de entradas no está disponible

#### Scenario: Cambio de estado repetido
- **WHEN** el sistema retoma una aprobación y repite el pedido de pasar la entrada a en reintegro, y Entradas responde 200
- **THEN** el paso queda hecho y la coordinación continúa sin duplicar efectos
