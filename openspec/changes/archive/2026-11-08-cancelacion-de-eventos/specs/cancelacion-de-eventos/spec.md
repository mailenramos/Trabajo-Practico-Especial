## Purpose

Cubre la recepción del aviso de cancelación de un evento y la devolución automática, sin
aprobación de un administrador, de lo pagado por las entradas vigentes de sus compras
confirmadas.

## ADDED Requirements

### Requirement: Recibir el aviso de cancelación de forma idempotente
El sistema SHALL aceptar el aviso de cancelación de un evento con su identificador, motivo
y fecha de cancelación, y SHALL procesarlo una sola vez por evento. Un aviso nuevo SHALL
responderse como aceptado y un aviso de un evento ya procesado SHALL responderse como ya
procesado, sin repetir efectos.

#### Scenario: Aviso nuevo
- **WHEN** el servicio de eventos avisa la cancelación de un evento por primera vez
- **THEN** el sistema responde que aceptó el aviso (202)
- **AND** coordina la devolución de las compras del evento

#### Scenario: Aviso repetido de un evento ya procesado
- **WHEN** el servicio de eventos repite el aviso de un evento cuya cancelación ya se procesó
- **THEN** el sistema responde que ya lo procesó (200)
- **AND** no pide ningún reintegro nuevo

#### Scenario: Aviso repetido mientras se procesa
- **WHEN** llega un aviso repetido y la cancelación de ese evento todavía se está procesando
- **THEN** el sistema responde que aceptó el aviso (202) y no inicia un segundo procesamiento

#### Scenario: Aviso repetido de una cancelación que quedó sin terminar
- **WHEN** llega un aviso repetido y la cancelación de ese evento quedó en proceso más allá del plazo, porque el servicio se cayó o una compra tuvo una falla inesperada
- **THEN** el sistema responde que aceptó el aviso (202) y retoma el procesamiento
- **AND** las compras que ya tienen su reintegro por la cancelación no generan otro

#### Scenario: Aviso incompleto
- **WHEN** el aviso no trae el identificador del evento
- **THEN** el sistema lo rechaza con un error de validación

### Requirement: Devolver automáticamente las compras confirmadas del evento
Al procesar la cancelación, el sistema SHALL pedir al servicio de entradas pasar a en
reintegro las entradas del evento y, por cada compra confirmada, SHALL pedir un reintegro
por el precio unitario de sus entradas vigentes, sin aprobación de un administrador. Las
compras pendientes o fallidas SHALL NOT generar reintegro.

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
- **WHEN** se procesa la cancelación y el evento tiene compras pendientes o fallidas
- **THEN** no se pide ningún reintegro por ellas

#### Scenario: Un reintegro falla y el resto sigue
- **WHEN** el reintegro de una compra falla o no tiene respuesta
- **THEN** queda registrado como fallido o pendiente
- **AND** las demás compras del evento se procesan igual

#### Scenario: La marcación de Entradas no informa entradas nuevas
- **WHEN** al repetirse el procesamiento la marcación del servicio de entradas responde que marcó 0 entradas
- **THEN** el sistema no pide otro reintegro por las compras que ya tienen el suyo
