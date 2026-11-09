## Purpose

Definir el contrato observable de MS2 para disponibilidad, reservas temporales y transición de reservas a ventas dentro de TicketYa.

## ADDED Requirements

### Requirement: MS2 identifiers and numbered-place identity
`eventoId`, `sectorId` and `clienteId` SHALL be Strings. `fila` and `numero` SHALL be Integers; `numero` SHALL be positive. A numbered place SHALL be identified only by `(eventoId, sectorId, fila, numero)`. MS2 SHALL NOT introduce a business `lugarId` or `asientoId`. Cross-service identifiers SHALL be scalar values with no physical foreign keys to other services.

Within an event, `sectorId` SHALL identify at most one sector, consistent with uniqueness of `(eventoId, sectorId)`. A numbered sector SHALL NOT contain duplicate `(fila, numero)` pairs; persistence uniqueness for a numbered place SHALL be `(eventoId, sectorId, fila, numero)`.

#### Scenario: Persist a numbered-place identity
- **WHEN** MS2 receives or stores a numbered place
- **THEN** it identifies the place by event, sector, integer row and positive integer number

### Requirement: MS2 event availability lifecycle states
MS2 SHALL preserve event lifecycle separately from sales suspension and inventory exhaustion. Initialization SHALL set the existing local event state to `PENDIENTE_PUBLICACION`; receiving `ACTIVA` through `PUT /disponibilidad/eventos/{eventoId}/venta` publishes the event (or reactivates sales after suspension). `SUSPENDIDA` is represented by the existing suspension behavior and does not change the event lifecycle. `CANCELADA` and `FINALIZADA` represent terminal lifecycle changes. Inventory exhaustion is derived only from confirmed sales and does not itself end or replace the event lifecycle. Although the existing `EstadoEvento` enum contains `AGOTADO`, MS2 SHALL derive exhaustion from inventory and SHALL NOT persist it by overwriting the event lifecycle; `ventaSuspendida` remains independent. No persisted event states or fields are added for exhaustion. MS1 remains authoritative for event lifecycle.

#### Scenario: Initialize before MS1 publishes
- **WHEN** MS2 commits a new event availability initialization
- **THEN** its local state is `PENDIENTE_PUBLICACION` until MS1 sends `ACTIVA` through the sales-state endpoint

#### Scenario: Publication follows the sales-state notification
- **WHEN** MS1 sends `ACTIVA` for an initialized event in `PENDIENTE_PUBLICACION`
- **THEN** MS2 applies the agreed publication behavior through the existing local model without introducing an endpoint or persisted state

### Requirement: Event availability is initialized from MS1
MS1 SHALL initialize availability through direct REST, outside the Gateway and without inter-service authentication, using `PUT /disponibilidad/eventos/{eventoId}`. The JSON body SHALL contain `limiteEntradasPorCliente` and `sectores`. `limiteEntradasPorCliente` SHALL be the fixed integer `4` for every event; it is not organizer-configurable. MS1 SHALL always send `4`, and MS2 SHALL validate that the received value is exactly `4`. The limit SHALL remain fixed throughout the event lifecycle and SHALL NOT be modified later; no limit-update endpoint is part of this contract. Each sector contains `sectorId`, `tipo`, `capacidadTotal` and `asientos`. Numbered sectors contain the complete list of seats with integer `fila` and positive integer `numero`; non-numbered sectors contain an empty `asientos` list. MS2 SHALL atomically persist the event, sectors and seats and initialize numbered seats as `LIBRE` and non-numbered reserved/sold quantities as zero. A successful initialization persists the event as `PENDIENTE_PUBLICACION` and is acknowledged only after commit.

An identical retry SHALL NOT duplicate or reset data. A retry with different content for an already initialized event SHALL return HTTP `409 Conflict` without changing existing data. A successful new initialization and an identical retry SHALL return HTTP `204 No Content`, only after commit. Invalid request data returns `400 Bad Request`, unsupported content type returns `415 Unsupported Media Type`, and unrecoverable internal errors return `500 Internal Server Error`. The fixed limit of `4` SHALL remain unchanged after initialization and publication; no update operation is part of this contract.

#### Scenario: Initialize a numbered and non-numbered event
- **WHEN** MS1 sends complete sector inventory, with numbered seats or `asientos: []` for non-numbered sectors
- **THEN** MS2 commits the complete inventory atomically, records the event as `PENDIENTE_PUBLICACION` and responds `204 No Content`

#### Scenario: Reject repeated sector or numbered-seat identities
- **WHEN** an initialization contains the same `sectorId` more than once for the event, or repeats a `(fila, numero)` pair within a numbered sector
- **THEN** MS2 rejects the initialization atomically and does not leave duplicate or partial inventory records

The numbered `asientos` list represents the complete sector inventory. Whether `capacidadTotal` SHALL equal the number of listed seats is not explicitly defined and remains pending; MS2 SHALL NOT enforce that equality until confirmed.

#### Scenario: Initialize with the fixed per-event limit
- **WHEN** MS1 initializes availability and sends `limiteEntradasPorCliente: 4`
- **THEN** MS2 persists the fixed limit of four entries for that event

#### Scenario: Reject a missing or non-four limit
- **WHEN** the initialization request omits the limit or supplies a value other than `4`
- **THEN** MS2 rejects the request as invalid and persists no event, sector or seat

#### Scenario: Keep the limit fixed from initialization
- **WHEN** an event's availability has been initialized
- **THEN** its effective client limit remains unchanged for the event lifecycle and MS2 exposes no limit-update operation

#### Scenario: Retry identical initialization
- **WHEN** MS1 repeats initialization with the same effective limit and inventory
- **THEN** MS2 returns HTTP `204 No Content` without duplicating or resetting existing records

#### Scenario: Reject changed initialization
- **WHEN** MS1 repeats initialization for an existing event with a different limit, sector or seat inventory
- **THEN** MS2 returns HTTP `409 Conflict` and leaves persisted availability unchanged

#### Scenario: Roll back failed initialization
- **WHEN** any event, sector or seat write fails
- **THEN** MS2 rolls back all writes and does not report successful initialization

### Requirement: MS1 sends the event sales state
MS1 SHALL send `PUT /disponibilidad/eventos/{eventoId}/venta` by direct REST with JSON `{ "estado": "ACTIVA" | "SUSPENDIDA" | "CANCELADA" | "FINALIZADA" }`. `ACTIVA` means published with sales enabled. `SUSPENDIDA` means published with sales suspended. `CANCELADA` and `FINALIZADA` block new reservations and release active temporary reservations while preserving confirmed sales. MS2 is responsible only for applying the event state locally, blocking new reservations and releasing applicable active temporary reservations; coordination of cancellation, refunds and ticket invalidation belongs to the responsible microservices. The contract includes all four values; task 2.5 implements activation/publication, suspension and resumption, while task 4.4 implements the terminal values after reservation and release transitions exist. A successful new transition and a repeated current state SHALL return `204 No Content`; an invalid state SHALL return `400 Bad Request`; if MS2 has no local availability initialization for the event it SHALL return `404 Not Found` without asserting that the event does not exist globally; an incompatible transition SHALL return `409 Conflict`; and an unrecoverable internal error SHALL return `500 Internal Server Error`. Unsupported content type returns `415 Unsupported Media Type`. MS1 remains authoritative for event lifecycle. The agreed functional mapping SHALL be implemented using the existing `EstadoEvento` and `ventaSuspendida`, without adding persisted event states.

#### Scenario: Activate sales
- **WHEN** MS1 sends `ACTIVA` after initialization
- **THEN** MS2 records the event as published with sales enabled and responds `204 No Content`

#### Scenario: Suspend and resume sales
- **WHEN** MS1 changes sales from active to suspended and later to active
- **THEN** MS2 blocks new reservations while suspended and preserves unexpired reservations; reactivation permits new reservations

#### Scenario: Repeat the same sales state
- **WHEN** MS1 repeats the currently applied sales state
- **THEN** MS2 responds with HTTP `204 No Content` and does not repeat side effects

#### Scenario: Reject an incompatible transition
- **WHEN** MS1 requests a transition that is incompatible with the current event state
- **THEN** MS2 responds with HTTP `409 Conflict`

#### Scenario: Reject an invalid sales state
- **WHEN** MS1 sends a state value outside `ACTIVA`, `SUSPENDIDA`, `CANCELADA` and `FINALIZADA`
- **THEN** MS2 leaves the current state unchanged and responds `400 Bad Request`

#### Scenario: Reject a sales-state update without local initialization
- **WHEN** MS1 sends a sales-state update for an event with no availability initialized in MS2
- **THEN** MS2 responds `404 Not Found` indicating that local availability is not registered, without asserting that the event does not exist globally

#### Scenario: Cancel or finalize an event
- **WHEN** MS1 sends `CANCELADA` or `FINALIZADA`
- **THEN** MS2 applies the local state, blocks new reservations, releases active temporary reservations once and preserves confirmed sales; it does not coordinate refunds or ticket invalidation

### Requirement: MS1 queries current availability
MS1 SHALL query `GET /disponibilidad/eventos/{eventoId}` using direct REST, outside the Gateway. A successful response SHALL be HTTP `200 OK` with JSON containing `eventoId` and `sectores`. Every sector SHALL contain `sectorId`, `tipo`, `capacidadTotal` and `cantidadDisponible`. For a numbered sector, `cantidadDisponible` SHALL equal the number of free seats returned in `asientosDisponibles`, whose entries contain integer `fila` and positive integer `numero`. For a non-numbered sector, the response SHALL omit `asientosDisponibles`, and available quantity SHALL be `capacidadTotal - cantidadReservada - cantidadVendida`. Reserved and sold numbered seats SHALL NOT be returned as available. `cantidadDisponible` MAY be zero because places are held by active temporary reservations; zero availability caused by reservations SHALL NOT mean the event is exhausted. Exhaustion is determined only by confirmed sales under the sold-out requirement. Missing local availability returns `404 Not Found`; unrecoverable internal errors return `500 Internal Server Error`.

#### Scenario: Query mixed sector types
- **WHEN** MS1 queries an initialized event with numbered and non-numbered sectors
- **THEN** MS2 returns availability by sector and only free numbered seats

#### Scenario: Query unavailable local event data
- **WHEN** MS1 queries an event with no local availability registered in MS2
- **THEN** MS2 returns `404 Not Found` and describes missing MS2 availability without claiming the event does not exist globally

#### Scenario: Query sold-out availability
- **WHEN** all places in an initialized event are sold
- **THEN** MS2 returns zero available quantity and an empty numbered-seat list where applicable

#### Scenario: Temporary reservations reduce availability without exhausting the event
- **WHEN** temporary reservations make a sector's `cantidadDisponible` zero while at least one place is not sold
- **THEN** MS2 returns zero available quantity but does not treat the event as exhausted or notify MS1 as sold out

### Requirement: Client reservations are atomic and temporary
The customer SHALL create a reservation through the Gateway using `POST /disponibilidad/eventos/{eventoId}/reservas`. The owner SHALL be derived from the required `X-User-Id` header, never from the request body. MS2 SHALL NOT require `X-User-Role`; authorization is handled by the Gateway. The body SHALL contain `solicitudId` and `detalles`. A numbered detail contains `sectorId`, integer `fila` and positive integer `numero`; a non-numbered detail contains `sectorId` and positive `cantidad`. Each temporary reservation SHALL last exactly 10 minutes; this duration is fixed and not configurable. MS2 SHALL reserve every requested place or quantity or none. It SHALL accept reservations only while event sales are active, and SHALL enforce capacity and the fixed maximum of 4 entries per client and event across active reservations and confirmed sales.

#### Scenario: Create a complete reservation
- **WHEN** a client requests available places or capacity for an active event and remains within the event limit
- **THEN** MS2 creates one temporary reservation, assigns its owner from `X-User-Id` and returns the reservation identifier and expiration

#### Scenario: Reject a partially unavailable request
- **WHEN** any requested numbered place is not free or requested non-numbered quantity exceeds capacity
- **THEN** MS2 rejects the entire request without leaving a partial reservation or inventory update

#### Scenario: Reject reservation when sales are not active
- **WHEN** availability is not initialized, the event is not published, is suspended, canceled, finalized or exhausted
- **THEN** MS2 rejects new reservations without allocating capacity

#### Scenario: Ignore a body-supplied owner
- **WHEN** a request body contains a client identifier that differs from `X-User-Id`
- **THEN** MS2 never assigns ownership from the body

### Requirement: Reservation creation recognizes retries
The request identity SHALL be `(clienteId, solicitudId)`, where `clienteId` is derived from `X-User-Id`; `eventoId` is not part of this idempotency key. The client SHALL preserve `solicitudId` across retries. For content comparison, MS2 SHALL include both the `eventoId` path parameter and the complete request body; therefore, using the same client and `solicitudId` with a different `eventoId` SHALL count as different content and return `409 Conflict`. If the same client repeats the same identifier, same `eventoId` and same body content while the original reservation remains `VIGENTE` and its expiration instant has not passed, MS2 SHALL return `200 OK` with the existing reservation and SHALL NOT reserve the places again. Reuse of that client/identifier with different content SHALL return `409 Conflict` without changing the original reservation. If the associated reservation has expired (even if automatic expiration has not yet changed its stored state) or is `LIBERADA`, MS2 SHALL return `409 Conflict` indicating that the original reservation is no longer active; it SHALL NOT create a new reservation automatically. A new request with a new identifier SHALL return `201 Created`. Requests for distinct clients SHALL have independent request identities and limits. Concurrent requests SHALL NOT double-book a numbered seat or exceed non-numbered capacity or a client/event limit.

Creating a new reservation SHALL return HTTP `201 Created`; an identical retry while the original remains `VIGENTE` SHALL return HTTP `200 OK`.

#### Scenario: Retry the same reservation request
- **WHEN** a client repeats a reservation request using the same `solicitudId` and same content
- **THEN** while the original remains `VIGENTE`, MS2 returns it with `200 OK` without duplicating holds

#### Scenario: Reuse request ID under a different event path
- **WHEN** the same client reuses a `solicitudId` with the same body but a different `eventoId` path parameter
- **THEN** MS2 includes the path event in content comparison and returns `409 Conflict` without changing the original reservation

#### Scenario: Reuse request ID with different content
- **WHEN** a client reuses a `solicitudId` with different details
- **THEN** MS2 returns `409 Conflict` and does not modify the original reservation

#### Scenario: Retry after the original reservation is no longer active
- **WHEN** the same client repeats the same request after its original reservation expired or was released
- **THEN** MS2 returns `409 Conflict`, indicates that the original reservation is no longer active, and does not create another reservation

#### Scenario: Same request ID for a different client
- **WHEN** a different client uses a `solicitudId` already used by another client
- **THEN** MS2 treats the request identity independently using the client and identifier combination

#### Scenario: Concurrent reservation requests
- **WHEN** concurrent operations request the same numbered seat, last capacity or remaining client limit
- **THEN** database-backed concurrency control allows only operations that preserve all capacity and limit invariants

### Requirement: Reservations and customer quantities stay consistent
Reservation states SHALL be `VIGENTE`, `CONFIRMADA` and `LIBERADA`. The maximum is fixed at exactly 4 entries per client and event from initialization; it is not configurable. MS2 SHALL count active temporary reservations and confirmed sales by client/event. Creating a reservation increments reserved quantity; confirmation transfers it from reserved to confirmed; payment rejection or expiration decrements reserved; approved refunds decrement confirmed for the returned subset. Each transition SHALL update reservation, inventory and client/event counts atomically and exactly once. Changing an event state to suspended SHALL preserve active reservations. Cancellation/finalization SHALL release active temporary reservations but SHALL NOT delete or release confirmed sales; MS2 only applies the local state and releases temporary reservations, while coordination of refunds and ticket invalidation belongs to the responsible services.

#### Scenario: Maintain counts during reservation transitions
- **WHEN** a reservation is created, confirmed, rejected/released, expires or is partially returned after approved refund
- **THEN** inventory and customer/event quantities change together exactly once

#### Scenario: Enforce the limit concurrently
- **WHEN** concurrent requests by one client could exceed the event limit
- **THEN** only requests whose combined active holds and confirmed sales remain within the limit succeed

#### Scenario: Keep a published event limit fixed
- **WHEN** the event is already published
- **THEN** no operation in this contract changes its initialized per-client limit

### Requirement: MS3 can inspect a reservation
MS3 SHALL query `GET /disponibilidad/reservas/{reservaId}` directly, outside the Gateway. An existing reservation returns HTTP `200 OK`; a reservation not registered in MS2 returns `404 Not Found`; unrecoverable internal errors return `500 Internal Server Error`. The response SHALL contain `reservaId`, `clienteId`, `eventoId`, reservation `estado`, `expiraEn` and `detalles`. `expiraEn` SHALL be represented in UTC using ISO-8601 and end in `Z`. Numbered details contain `sectorId`, integer `fila` and `numero`; non-numbered details contain `sectorId` and `cantidad`.

#### Scenario: Inspect a reservation before purchase processing
- **WHEN** MS3 queries an existing reservation
- **THEN** MS2 returns its owner, event, state, expiration and reserved details

The existing customer self-query requirement is retained: a client may inspect only a reservation owned by the `X-User-Id` in the request. Its public route and response contract are not part of the current MS1/MS3 integration agreement and remain pending confirmation; the MS3 query above does not replace that use case.

#### Scenario: Client inspects their own reservation
- **WHEN** a client requests a reservation whose owner matches `X-User-Id`
- **THEN** MS2 returns the reservation data

#### Scenario: Client cannot inspect another client's reservation
- **WHEN** a client requests a reservation owned by a different `X-User-Id`
- **THEN** MS2 rejects access without disclosing the reservation data

### Requirement: Sales are confirmed only while the reservation is valid
MS3 SHALL request confirmation using `POST /disponibilidad/reservas/{reservaId}/confirmacion` after payment approval. MS2 SHALL confirm only a `VIGENTE` reservation whose expiration instant is later than the current instant, atomically moving reserved places/quantities to sold/confirmed. Confirmation SHALL be all-or-nothing. A successful confirmation, including a repeat of an already confirmed reservation, SHALL return `204 No Content`. A reservation not registered in MS2 returns `404 Not Found`. An expired reservation SHALL return `409 Conflict` with code `RESERVA_VENCIDA`; a released reservation SHALL return `409 Conflict` with code `RESERVA_LIBERADA`. MS2 SHALL NOT sell places for either rejected case; MS3 coordinates compensating treatment with MS4 if payment was already approved. Unrecoverable internal errors return `500 Internal Server Error`.

#### Scenario: Confirm an unexpired reservation
- **WHEN** MS3 confirms a VIGENTE reservation before its expiration
- **THEN** MS2 marks it CONFIRMADA, changes all its inventory to sold and responds 204

#### Scenario: Confirm after real expiration
- **WHEN** the expiration instant has passed even though cleanup has not run
- **THEN** MS2 rejects with `RESERVA_VENCIDA` and does not sell the places

#### Scenario: Repeat a completed confirmation
- **WHEN** MS3 repeats confirmation for an already CONFIRMADA reservation
- **THEN** MS2 responds 204 without duplicating sale or count updates

#### Scenario: Reject confirmation after release
- **WHEN** MS3 confirms a LIBERADA reservation
- **THEN** MS2 responds with `409 Conflict` and code `RESERVA_LIBERADA`

### Requirement: Reservations expire and payment rejection releases them
MS2 SHALL release a VIGENTE reservation at its stored expiration, using the actual current instant, and SHALL expose `POST /disponibilidad/reservas/{reservaId}/liberacion` to MS3 for payment-rejection release. `PAGO_RECHAZADO` releases the complete temporary reservation; it SHALL NOT release a CONFIRMADA reservation. Repeated release SHALL NOT repeat inventory or customer count updates. Successful release and a repeated already-applied release SHALL return `204 No Content`. Invalid request data returns `400 Bad Request`; a reservation not registered in MS2 returns `404 Not Found`; an incompatible state or transition returns `409 Conflict`; unrecoverable internal errors return `500 Internal Server Error`.

#### Scenario: Expire a reservation
- **WHEN** a VIGENTE reservation reaches its expiration
- **THEN** MS2 marks it LIBERADA and releases all its reserved places/capacity once

#### Scenario: Release after rejected payment
- **WHEN** MS3 requests `PAGO_RECHAZADO` for a VIGENTE reservation
- **THEN** MS2 releases it atomically; repeating the same release returns 204 without further changes

#### Scenario: Do not release a confirmed sale as payment rejection
- **WHEN** MS3 sends `PAGO_RECHAZADO` for a CONFIRMADA reservation
- **THEN** MS2 rejects the incompatible transition and does not free sold inventory

### Requirement: Approved refunds release selected places partially and idempotently
After MS3 invalidates the corresponding ticket in MS5, MS3 SHALL request release with `motivo: DEVOLUCION_APROBADA`, a globally unique UUID `devolucionId` generated when MS3 registers the refund, and the returned `detalles`. MS2 SHALL use `devolucionId` exclusively as the global idempotency key; it SHALL NOT combine it with `clienteId`. MS2 SHALL first recognize a previously processed identifier: the same ID with the same reservation and details SHALL return `204 No Content` without repeating changes; reuse with another reservation or different details SHALL return `409 Conflict` without changes. For a first-seen ID, every requested detail SHALL belong to the reservation identified by `reservaId`, and that reservation SHALL be `CONFIRMADA`; details not in that reservation or no longer sold/confirmed SHALL be rejected without modifying data. MS2 SHALL release only the specified sold seats/quantities and decrement confirmed client/event quantities atomically. Places not included in a partial refund SHALL remain sold.

The order of details SHALL NOT change request identity; MS2 SHALL normalize and sort them before calculating the request fingerprint. Duplicate details in one request SHALL be rejected with `400 Bad Request` and SHALL NOT be combined. The reservation SHALL remain `CONFIRMADA`, including after all its details have been returned; refund history is stored separately. The durable refund record and its source-detail ledger SHALL be written atomically with inventory and customer/event counter updates. A numbered source detail SHALL be returned at most once. For non-numbered details, cumulative returned quantity for each original reservation detail SHALL NOT exceed its original sold quantity, including across distinct `devolucionId` values.

Reusing an already processed `devolucionId` with another reservation or different detail content SHALL return `409 Conflict` and SHALL NOT process the refund or modify inventory or customer/event quantities. Independently of the idempotency key, MS2 SHALL prevent a seat or non-numbered quantity already released by a prior approved refund from being released again through another `devolucionId`; such a conflicting request SHALL NOT make inventory available again or decrement confirmed client/event quantities again.

#### Scenario: Apply a partial numbered-seat refund
- **WHEN** an approved refund identifies a subset of seats from a confirmed reservation
- **THEN** MS2 releases only those seats and leaves the remaining seats sold

#### Scenario: Apply a partial non-numbered refund
- **WHEN** an approved refund identifies a quantity smaller than the confirmed quantity
- **THEN** MS2 releases only that quantity and preserves the remaining sold quantity

#### Scenario: Retry the same approved refund
- **WHEN** MS3 repeats a processed refund with the same `devolucionId` and content
- **THEN** MS2 returns 204 without releasing or decrementing twice

#### Scenario: Reuse global refund identifier with another reservation or details
- **WHEN** a processed `devolucionId` is reused with another reservation or with different details
- **THEN** MS2 returns `409 Conflict` using the agreed error format and makes no further changes

#### Scenario: Do not release the same sold place twice with different refund identifiers
- **WHEN** an approved refund requests a seat or quantity already released by an earlier refund under another `devolucionId`
- **THEN** MS2 rejects the conflicting release with `409 Conflict`, without making the place available again or decrementing confirmed quantities again

#### Scenario: Reject first-time refund details outside the confirmed reservation
- **WHEN** a first-seen `devolucionId` requests a place or quantity that is not part of the identified confirmed reservation, or that is no longer sold/confirmed
- **THEN** MS2 rejects the request with `409 Conflict` and does not modify inventory or client/event quantities

### Requirement: MS2 reports sold-out state transitions to MS1
A numbered sector is sold out only when all its places are `VENDIDO`; a non-numbered sector is sold out only when all capacity is sold. Active reservations do not count as sales. Therefore, `cantidadDisponible` can be zero due to active reservations while the event is not sold out. The event is sold out only when every sector is sold out. MS2 SHALL call MS1 at `PUT /eventos/{eventoId}/disponibilidad` with `{"agotado":true}` when the condition changes to sold out and `{"agotado":false}` when it changes back to available. It SHALL NOT notify when the condition has not changed. Expiration or payment rejection alone does not change sold-out state based on confirmed sales. Sold-out condition is independent of event lifecycle and `ventaSuspendida`; MS2 SHALL NOT add persisted states to represent this distinction. Initialization SHALL NOT create an initial Outbox notification; notifications represent transitions caused by subsequent confirmed-sales or approved-refund operations.

MS2 SHALL persist a notification in its Outbox in the same database transaction as the confirmation or approved refund that changes the derived sold-out condition. A failure of that transaction SHALL roll back both the inventory/counter transition and the Outbox row. MS2 SHALL NOT make the HTTP request to MS1 inside that transaction. The Outbox SHALL retain the event, target `agotado` value, creation time, delivery status, attempt count, next-attempt time and delivery/error trace. Delivery claims SHALL prevent concurrent workers from sending the same row at once and SHALL be recoverable after a worker restart. Notifications for one event SHALL be delivered in transition order. A successful MS1 `204` marks the row sent; transport failures or any other HTTP status remain durable and are retried with progressive capped delay, without a finite attempt discard limit. This is at-least-once delivery: a process failure after MS1 accepts a request but before MS2 records the `204` can result in a later duplicate request, which MS1 handles idempotently.

#### Scenario: Notify sold out once on transition
- **WHEN** all sectors become sold out through confirmed sales
- **THEN** MS2 sends `agotado: true` once for that state transition

#### Scenario: Notify restored availability once
- **WHEN** an approved refund makes a previously sold-out event available again
- **THEN** MS2 sends `agotado: false` once for that state transition

#### Scenario: Store an exhaustion transition atomically
- **WHEN** confirmation or an approved refund changes the event's derived sold-out condition
- **THEN** MS2 commits the inventory/counter update and one matching Outbox row together, without sending HTTP in that transaction

#### Scenario: Do not notify on a failed or unchanged transition
- **WHEN** the sales/inventory transaction fails or the derived sold-out condition remains unchanged
- **THEN** MS2 does not leave a notification row for that operation

#### Scenario: Retry pending notifications after delivery failure or restart
- **WHEN** MS1 is unavailable, returns a status other than `204`, or MS2 restarts with an unacknowledged notification
- **THEN** the notification remains durable, is retried after a progressively increasing delay, and is not discarded due to attempt count

#### Scenario: Refund restores inventory while sales remain suspended
- **WHEN** an approved refund changes a suspended event from sold out to not sold out
- **THEN** MS2 notifies `agotado: false` but keeps sales suspended and does not change the event lifecycle

MS1 acknowledges processing with `204`, including repeated values and late notifications for canceled/finalized events; it returns `404` for an unknown event and `409` only while the event is a draft. MS2 durably records and retries delivery as specified above; retry requests preserve the agreed state-setting payload.

### Requirement: Internal REST calls and user identity remain separate
Internal calls between MS1, MS2 and MS3 SHALL use direct REST/JSON outside the Gateway and SHALL NOT require service credentials or Bearer tokens. For customer-facing calls routed through the Gateway, the Gateway SHALL manage authorization and transmit the shared `X-User-Role` header using exactly one of the case-sensitive values `CLIENTE`, `ORGANIZADOR` or `ADMIN`. These values are the common role vocabulary across microservices. For customer reservation operations, the Gateway supplies `X-User-Id`; MS2 SHALL use it as reservation owner and never accept a body field as its substitute. MS2 SHALL NOT require or validate `X-User-Role` on its endpoints. This shared role header does not add service-to-service authentication or change direct internal REST calls. User JWT authentication belongs to the second delivery and does not authenticate internal services.

#### Scenario: Create using Gateway user identity
- **WHEN** the client creates a reservation through the Gateway
- **THEN** MS2 stores the `X-User-Id` value as the reservation owner

#### Scenario: Keep internal service identity separate
- **WHEN** MS1 or MS3 calls MS2 directly
- **THEN** the request does not use service Bearer credentials or pass through the Gateway

### Requirement: MS2 uses the agreed error format
MS2 error responses SHALL use the agreed JSON shape `timestamp`, `status`, `error`, `mensaje`, and optional `campos`, where each item contains `campo` and `mensaje`, and SHALL use the agreed error-code catalog. Error responses SHALL NOT expose SQL, exception details or stack traces.

#### Scenario: Describe invalid input using the agreed format
- **WHEN** an HTTP request contains malformed JSON or invalid/missing required data
- **THEN** the response is HTTP 400 with the agreed error fields and optional field violations

#### Scenario: Describe local availability absence
- **WHEN** MS2 has no availability registered for a requested event
- **THEN** it responds 404 without asserting that the event does not exist globally

#### Scenario: Describe conflicts and unexpected failures safely
- **WHEN** a defined operation encounters an incompatible state, unsupported media type or unrecoverable internal failure
- **THEN** MS2 uses 409, 415 or 500 as applicable and does not expose internal diagnostics

### Requirement: MS2 reports availability notification outcomes to MS1
When the sold-out condition changes, MS2 SHALL call MS1 directly using `PUT /eventos/{eventoId}/disponibilidad` with `{"agotado": true}` or `{"agotado": false}`. MS2 SHALL notify only on a change. MS1 responds `204 No Content` when it processes the notification, including when the value is unchanged or the notification arrives late for a canceled or finalized event; it responds `404 Not Found` when the event is unknown and `409 Conflict` only while the event remains a draft. Internal communication is direct REST/JSON, outside the Gateway and without inter-service authentication. The local ports are MS1 `8081`, MS2 `8080` and MS3 `8083`.

#### Scenario: Acknowledge an availability-state notification
- **WHEN** MS1 processes an availability notification, including a repeated or late notification for a canceled/finalized event
- **THEN** MS1 responds `204 No Content`

#### Scenario: Reject a notification for an unknown or draft event
- **WHEN** MS2 notifies MS1 about an event that does not exist or remains a draft
- **THEN** MS1 responds `404 Not Found` for the unknown event or `409 Conflict` only for the draft event

## Decisions Pending Confirmation

- Inventory of each target environment before applying Flyway V4. V4 converts only integer values within Java `Integer` range and aborts before DDL for non-numeric values, out-of-range values or collisions in `(evento_id, sector_id, fila, numero)`. Any remediation of incompatible legacy values requires explicit approval; no arbitrary mapping or deletion is allowed.
- Compatibility of retries for events with legacy, unversioned initialization fingerprints. Such fingerprints cannot currently verify equality and are rejected as conflicts until a verifiable compatibility strategy is approved.
- Whether `capacidadTotal` for a numbered sector SHALL equal the number of entries in its complete `asientos` list; no equality validation is required until the team confirms this invariant.
