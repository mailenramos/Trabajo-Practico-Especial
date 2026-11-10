# Spec Delta

## MODIFIED Requirements

### Requirement: Persistir transaccionalmente la emisión y sus entradas
El servicio MUST ejecutar la emisión dentro de una transacción, crear una entrada por cada lugar solicitado con un UUID de QR generado y estado inicial `VIGENTE`, y usar las restricciones de base de datos como protección final contra compras duplicadas y asientos numerados vigentes duplicados.

#### Scenario: Se emite una compra nueva
- **WHEN** no existe una emisión para la compra y todas las inserciones tienen éxito
- **THEN** el servicio persiste la emisión y las entradas solicitadas y devuelve un resultado de creación nueva

#### Scenario: Una solicitud concurrente colisiona por identificador de compra
- **WHEN** otra transacción gana la inserción única de `solicitudes_emision.id_compra`
- **THEN** el servicio captura el error de integridad, inicia una transacción nueva para buscar una emisión coincidente y la devuelve si existe

#### Scenario: El asiento numerado ya está vigente
- **WHEN** ya existe una entrada `VIGENTE` para el mismo evento, sector, fila y número de asiento
- **THEN** el índice único parcial de PostgreSQL rechaza el asiento vigente duplicado y el servicio convierte una colisión no resuelta en `ENTRADA_VIGENTE_DUPLICADA`

#### Scenario: Solicitudes concurrentes para el mismo asiento
- **WHEN** se procesan concurrentemente dos o más solicitudes de emisión con distintos `idCompra` para el mismo evento, sector, fila y número de asiento, y no existe previamente una entrada vigente para ese asiento
- **THEN** exactamente una solicitud se emite correctamente, todas las restantes terminan con el conflicto `ENTRADA_VIGENTE_DUPLICADA` y PostgreSQL contiene exactamente una entrada `VIGENTE` para ese asiento
