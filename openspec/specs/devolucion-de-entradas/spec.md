# devolucion-de-entradas Specification

## Purpose
Cubre la solicitud de devolución de una entrada por parte de su titular, su resolución por
un administrador, la coordinación de una devolución aprobada con los servicios de
entradas, reservas y pagos, y la consulta de las solicitudes.

## Requirements

### Requirement: Solicitar la devolución de una entrada
El sistema SHALL permitir a un cliente solicitar la devolución de una entrada vigente que
le pertenece cuando el evento empieza en más de 72 horas. El sistema SHALL validar la
titularidad y el estado de la entrada con el servicio de entradas y la fecha de inicio con
el servicio de eventos, y SHALL registrar la solicitud en estado pendiente con un
identificador propio.

#### Scenario: Solicitud dentro del plazo
- **WHEN** un cliente solicita la devolución de una entrada vigente propia de un evento que empieza en más de 72 horas
- **THEN** el sistema registra una solicitud pendiente con un identificador de devolución
- **AND** el monto a reintegrar es el precio unitario pagado por esa entrada

#### Scenario: Plazo vencido
- **WHEN** un cliente solicita la devolución de una entrada de un evento que empieza en 72 horas o menos
- **THEN** el sistema rechaza la solicitud indicando que venció el plazo
- **AND** no registra ninguna solicitud

#### Scenario: Entrada que no está vigente
- **WHEN** un cliente solicita la devolución de una entrada en reintegro o devuelta
- **THEN** el sistema rechaza la solicitud

#### Scenario: Entrada de otro cliente
- **WHEN** un cliente solicita la devolución de una entrada que pertenece a otro cliente
- **THEN** el sistema rechaza la solicitud por falta de permiso

#### Scenario: Entrada inexistente
- **WHEN** un cliente solicita la devolución de una entrada que no corresponde a ninguna compra
- **THEN** el sistema responde que la entrada no existe

#### Scenario: Evento cancelado
- **WHEN** un cliente solicita la devolución de una entrada de un evento cancelado
- **THEN** el sistema rechaza la solicitud, porque esos reintegros son automáticos

#### Scenario: Otro servicio no responde
- **WHEN** el servicio de entradas o el de eventos no responde al validar la solicitud
- **THEN** el sistema informa que no pudo registrarla
- **AND** no registra ninguna solicitud

### Requirement: Una sola solicitud activa por entrada
El sistema SHALL mantener como máximo una solicitud pendiente o aprobada por entrada. Si el
mismo cliente repite la solicitud de una entrada que ya tiene una solicitud pendiente, el
sistema SHALL devolver esa solicitud sin crear otra.

#### Scenario: Solicitud repetida
- **WHEN** un cliente solicita la devolución de una entrada que ya tiene una solicitud pendiente suya
- **THEN** el sistema devuelve la solicitud existente
- **AND** no crea un duplicado

#### Scenario: Dos solicitudes simultáneas de la misma entrada
- **WHEN** llegan al mismo tiempo dos solicitudes de devolución de la misma entrada
- **THEN** el sistema registra una sola solicitud
- **AND** ambos pedidos reciben esa solicitud

#### Scenario: Nueva solicitud después de un rechazo
- **WHEN** un cliente solicita la devolución de una entrada vigente cuya solicitud anterior fue rechazada
- **THEN** el sistema registra una solicitud nueva

### Requirement: Estados de la solicitud de devolución
El sistema SHALL manejar tres estados de solicitud: pendiente, aprobada y rechazada,
independientes del estado del reintegro. La solicitud SHALL pasar a aprobada o rechazada
solo desde pendiente y SHALL NOT volver a resolverse.

#### Scenario: Solicitud ya resuelta
- **WHEN** un administrador intenta aprobar o rechazar una solicitud que ya está aprobada o rechazada
- **THEN** el sistema rechaza la operación
- **AND** la solicitud conserva su estado

#### Scenario: Dos resoluciones simultáneas
- **WHEN** llegan al mismo tiempo dos resoluciones de la misma solicitud pendiente
- **THEN** solo una se aplica
- **AND** la otra se rechaza como solicitud ya resuelta

### Requirement: Solo un administrador resuelve devoluciones
El sistema SHALL permitir aprobar o rechazar solicitudes de devolución, y reintentar
reintegros, únicamente a usuarios con rol de administrador, identificado por el encabezado
X-User-Role.

#### Scenario: Usuario que no es administrador
- **WHEN** un usuario sin rol de administrador intenta aprobar o rechazar una solicitud
- **THEN** el sistema le deniega el acceso
- **AND** la solicitud no cambia

### Requirement: Rechazar una solicitud
Cuando un administrador rechaza una solicitud pendiente indicando un motivo, el sistema
SHALL dejarla rechazada con ese motivo registrado y SHALL NOT pedir ningún cambio a otros
servicios.

#### Scenario: Rechazo con motivo
- **WHEN** un administrador rechaza una solicitud pendiente indicando un motivo
- **THEN** la solicitud queda rechazada con el motivo registrado
- **AND** la entrada sigue vigente

#### Scenario: Rechazo sin motivo
- **WHEN** un administrador intenta rechazar una solicitud sin indicar un motivo
- **THEN** el sistema rechaza el pedido con un error de validación

### Requirement: Aprobar una solicitud coordina la devolución
Cuando un administrador aprueba una solicitud pendiente, el sistema SHALL dejarla aprobada
y, en este orden, SHALL pedir al servicio de entradas pasar la entrada a en reintegro,
pedir al servicio de reservas liberar el lugar con el identificador de devolución, pedir
al servicio de pagos el reintegro del precio unitario y, con el reintegro exitoso, pedir
al servicio de entradas pasar la entrada a devuelta.

#### Scenario: Aprobación completa
- **WHEN** un administrador aprueba una solicitud pendiente y todos los servicios responden bien
- **THEN** la solicitud queda aprobada
- **AND** la entrada pasa a en reintegro, el lugar se libera y se pide el reintegro del precio unitario
- **AND** con el reintegro exitoso la entrada pasa a devuelta

#### Scenario: Reintegro fallido al aprobar
- **WHEN** un administrador aprueba una solicitud y el servicio de pagos no logra el reintegro
- **THEN** la solicitud queda aprobada y el reintegro fallido
- **AND** la entrada permanece en reintegro

### Requirement: Una aprobación incompleta se retoma sin revertir
Si al aprobar falla una llamada distinta del reintegro, el sistema SHALL dejar la
solicitud aprobada, SHALL registrar qué pasos se hicieron y cuál falló, y SHALL NOT
revertir los pasos ya hechos. Un reintento SHALL continuar desde el paso que falló, sin
repetir los efectos de los pasos ya hechos.

#### Scenario: Falla al pasar la entrada a en reintegro
- **WHEN** un administrador aprueba una solicitud y el servicio de entradas no responde
- **THEN** la solicitud queda aprobada con la coordinación incompleta
- **AND** no se libera el lugar ni se pide el reintegro

#### Scenario: Falla al liberar el lugar
- **WHEN** la entrada ya pasó a en reintegro y el servicio de reservas no responde
- **THEN** la solicitud queda aprobada con la coordinación incompleta
- **AND** no se pide el reintegro

#### Scenario: Retomar una aprobación incompleta
- **WHEN** un administrador reintenta una devolución aprobada con la coordinación incompleta
- **THEN** el sistema continúa desde el paso que falló
- **AND** no repite los pasos ya hechos

#### Scenario: Paso repetido en otro servicio
- **WHEN** se repite un pedido a entradas o a reservas que ya se había aplicado
- **THEN** no se generan cambios duplicados

### Requirement: Consultar las solicitudes de devolución
El sistema SHALL permitir a un cliente consultar sus solicitudes de devolución, mostrando
por separado el estado de cada solicitud y el de su reintegro, y el motivo cuando fue
rechazada. Un administrador SHALL poder consultar todas las solicitudes y filtrarlas por
estado.

#### Scenario: Cliente con solicitudes
- **WHEN** un cliente consulta sus solicitudes de devolución
- **THEN** ve solo las suyas
- **AND** cada una muestra el estado de la solicitud y, por separado, el de su reintegro

#### Scenario: Solicitud rechazada
- **WHEN** un cliente consulta una solicitud rechazada
- **THEN** ve el motivo del rechazo

#### Scenario: Solicitud de otro cliente
- **WHEN** un cliente intenta consultar una solicitud de otro cliente
- **THEN** el sistema le deniega el acceso

#### Scenario: Administrador consulta las pendientes
- **WHEN** un administrador consulta las solicitudes en estado pendiente
- **THEN** ve las pendientes de todos los clientes

### Requirement: Una solicitud pendiente se cierra si el evento se cancela
Al procesar la cancelación de un evento, el sistema SHALL dejar rechazada, con el motivo
"Evento cancelado: reintegro automático", cada solicitud de devolución pendiente de una
entrada de ese evento, porque esa entrada se reintegra con la cancelación.

#### Scenario: Solicitud pendiente de un evento cancelado
- **WHEN** se procesa la cancelación de un evento con una solicitud de devolución pendiente
- **THEN** la solicitud queda rechazada con ese motivo
- **AND** su entrada se incluye en el reintegro por la cancelación

#### Scenario: Aprobar después de la cancelación
- **WHEN** un administrador intenta aprobar una solicitud que se cerró por la cancelación
- **THEN** el sistema la rechaza como solicitud ya resuelta

#### Scenario: Aprobación simultánea con la cancelación
- **WHEN** un administrador aprueba una solicitud pendiente mientras se procesa la cancelación de la compra de esa entrada
- **THEN** la aprobación espera a que termine ese procesamiento y la rechaza como solicitud ya resuelta
- **AND** la entrada queda incluida en un solo reintegro

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
