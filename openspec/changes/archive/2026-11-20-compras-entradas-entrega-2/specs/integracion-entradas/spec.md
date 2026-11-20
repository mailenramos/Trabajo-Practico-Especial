## ADDED Requirements

### Requirement: Estado UTILIZADA de una entrada
El servicio SHALL reconocer el estado `UTILIZADA` de una entrada y SHALL rechazar toda solicitud de devolución de una entrada que no esté VIGENTE, nombrando su estado.

#### Scenario: Entrada utilizada
- **WHEN** un cliente solicita la devolución de una entrada UTILIZADA
- **THEN** responde 409 con el código `ENTRADA_NO_VIGENTE` y el mensaje indica el estado UTILIZADA

#### Scenario: Estado desconocido
- **WHEN** Entradas informa un estado que el servicio no conoce
- **THEN** se interpreta como `DESCONOCIDO` y no se lo considera vigente

### Requirement: Aprobación de una devolución cuya entrada fue utilizada
Si Entradas responde 409 `ENTRADA_UTILIZADA` al pasar una entrada a reintegro durante la aprobación de una devolución, el servicio SHALL conservar la solicitud APROBADA con el error guardado y SHALL NOT pedir el reintegro de dinero.

#### Scenario: Entrada usada entre la solicitud y la aprobación
- **WHEN** el administrador aprueba una solicitud y Entradas responde 409 `ENTRADA_UTILIZADA`
- **THEN** la solicitud queda APROBADA con el código del conflicto en su último error y no se llama a Pagos

### Requirement: Identificación en las llamadas a Entradas
Todas las llamadas del servicio a Entradas SHALL incluir `X-Service-Token`, con el secreto configurado por entorno, y `X-Correlation-Id`.

#### Scenario: Llamada desde un pedido con correlación
- **WHEN** el servicio llama a Entradas durante un pedido que trae `X-Correlation-Id`
- **THEN** reenvía ese mismo valor

#### Scenario: Llamada sin correlación entrante
- **WHEN** la llamada nace de una tarea programada o no hay `X-Correlation-Id` entrante
- **THEN** el servicio genera un identificador nuevo y lo envía

#### Scenario: Token no configurado
- **WHEN** el secreto no está configurado
- **THEN** el servicio no envía el encabezado y trata la respuesta 403 de Entradas como una falla técnica reintentable

### Requirement: Cierre confirmado al cancelar un evento
La cancelación de un evento SHALL no darse por completa hasta que Entradas responda `cerrado: true` a `POST /internal/eventos/{idEvento}/entradas/reintegro`.

#### Scenario: Cierre confirmado
- **WHEN** Entradas responde `entradasMarcadas` y `cerrado: true`
- **THEN** el paso de Entradas se marca completo

#### Scenario: Cierre no confirmado
- **WHEN** la respuesta no trae `cerrado` o trae `cerrado: false`
- **THEN** el paso queda incompleto con el error guardado y se reintenta sin duplicar efectos

#### Scenario: Evento sin entradas
- **WHEN** el evento no tiene entradas vigentes y Entradas responde `cerrado: true`
- **THEN** el paso se marca completo aunque `entradasMarcadas` sea 0

### Requirement: Emisión rechazada por evento cerrado
Si Entradas responde 409 `EVENTO_CERRADO` al emitir las entradas de una compra cobrada, el servicio SHALL no reintentar la emisión, SHALL registrar el reintegro automático del total pagado y SHALL avisar al comprador con `EVENTO_CANCELADO`.

#### Scenario: Pago confirmado después del cierre
- **WHEN** se confirma el pago de una compra y la emisión responde 409 `EVENTO_CERRADO`
- **THEN** no se emiten entradas, se registra el reintegro automático por el total y se guarda un aviso `EVENTO_CANCELADO` para el comprador

#### Scenario: Repetición
- **WHEN** el mismo caso se procesa de nuevo
- **THEN** no se crea un segundo reintegro ni un segundo aviso

#### Scenario: Compra ya emitida antes del cierre
- **WHEN** la compra ya tenía entradas emitidas y se repite la emisión con los mismos datos
- **THEN** Entradas devuelve las mismas entradas y la compra no se trata como evento cerrado

### Requirement: Consulta de las entradas de una compra
El servicio SHALL poder consultar `GET /internal/compras/{idCompra}/entradas` y SHALL interpretar una lista vacía como "no se emitió".

#### Scenario: Emisión incierta con entradas
- **WHEN** una emisión dio timeout y la consulta devuelve entradas
- **THEN** el servicio asigna esas entradas a la compra sin volver a emitir

#### Scenario: Emisión incierta sin entradas
- **WHEN** la consulta devuelve una lista vacía
- **THEN** el servicio emite como de costumbre
