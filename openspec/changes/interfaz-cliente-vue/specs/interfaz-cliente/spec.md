## ADDED Requirements

### Requirement: Búsqueda y disponibilidad de eventos
La interfaz SHALL permitir buscar eventos publicados por texto y filtros básicos y consultar desde el Gateway el detalle, los sectores y la disponibilidad de un evento.

#### Scenario: Consultar eventos
- **WHEN** el cliente busca eventos con o sin filtros
- **THEN** la interfaz consulta `GET /eventos` en el Gateway y presenta los resultados y su paginación.

#### Scenario: Consultar sectores y lugares
- **WHEN** el cliente abre un evento
- **THEN** la interfaz consulta `GET /eventos/{eventoId}` en el Gateway y muestra sectores, precios y la disponibilidad recibida.

### Requirement: Reserva de lugares
La interfaz SHALL permitir seleccionar asientos disponibles de un sector numerado o una cantidad de lugares de un sector no numerado y crear una reserva por el Gateway con una clave idempotente que se reutilice ante una respuesta ambigua.

#### Scenario: Reservar un asiento o una cantidad
- **WHEN** el cliente confirma una selección válida
- **THEN** la interfaz envía `POST /disponibilidad/eventos/{eventoId}/reservas` con `X-User-Id`, una `solicitudId` nueva y los detalles con el formato requerido por el sector.

#### Scenario: Respuesta de reserva ambigua
- **WHEN** no se obtiene una respuesta HTTP al crear la reserva
- **THEN** el cliente reintenta la misma solicitud con la misma `solicitudId` y el mismo contenido.

#### Scenario: La disponibilidad cambió
- **WHEN** MS2 rechaza la reserva porque el lugar ya no está disponible
- **THEN** la interfaz muestra el error recibido y permite volver a consultar el evento antes de intentar otra reserva.

### Requirement: Compra y pago simulado
La interfaz SHALL permitir iniciar una compra con una reserva vigente y enviar el pago al Gateway con una clave de idempotencia que se reutilice ante una respuesta ambigua y cambie despues de una respuesta HTTP.

#### Scenario: Completar compra
- **WHEN** el cliente inicia la compra y confirma el pago simulado
- **THEN** la interfaz invoca `POST /compras` y `POST /compras/{compraId}/pago` a través del Gateway y muestra el estado devuelto.

#### Scenario: Pago con error o servicio no disponible
- **WHEN** el pago devuelve un error
- **THEN** la interfaz presenta el error y conserva la compra para que el cliente pueda reintentar sin iniciar otra.

#### Scenario: Respuesta de pago ambigua
- **WHEN** la solicitud de pago no obtiene respuesta HTTP o el servidor responde con un error 5xx
- **THEN** el cliente reintenta con la misma `Idempotency-Key` para evitar duplicar el efecto del pago.

### Requirement: Consulta de entradas emitidas
La interfaz SHALL permitir consultar y refrescar las entradas del usuario a través del Gateway.

#### Scenario: Ver entradas
- **WHEN** el cliente abre o refresca la sección de entradas
- **THEN** la interfaz consulta `GET /entradas` con la identidad del cliente y muestra estado, evento, sector y asiento.
