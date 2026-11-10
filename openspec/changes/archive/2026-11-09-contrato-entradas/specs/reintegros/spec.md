## ADDED Requirements

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
