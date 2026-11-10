## ADDED Requirements

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
