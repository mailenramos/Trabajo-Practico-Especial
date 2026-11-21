## ADDED Requirements

### Requirement: Inicialización y control de inventario de MS2
MS2 SHALL conservar la inicialización idempotente de disponibilidad por evento recibida de MS1 y controlar transaccionalmente inventario numerado y no numerado. MS2 SHALL mantener las operaciones de estado de venta, suspensión, cancelación y finalización de la Entrega 1 sin asumir responsabilidades de pago o emisión de entradas. US-4.1, US-5.1, US-5.2.

#### Scenario: Inicialización repetida
- **WHEN** MS1 repite la inicialización idéntica de un evento
- **THEN** MS2 conserva el inventario existente y responde 204 sin duplicarlo

#### Scenario: Inicialización incompatible
- **WHEN** MS1 intenta reinicializar un evento con contenido diferente
- **THEN** MS2 responde 409 sin reemplazar inventario

#### Scenario: Venta suspendida o evento terminal
- **WHEN** MS2 recibe suspensión, cancelación o finalización de venta
- **THEN** bloquea reservas nuevas; la suspensión conserva las reservas vigentes y la cancelación o finalización libera las temporales sin modificar las ventas confirmadas

### Requirement: Resumen de disponibilidad por sector
MS2 SHALL proporcionar capacidad total, disponibilidad, lugares reservados temporalmente y vendidos por sector; SHALL contar estados de asientos para numerados y usar contadores existentes para no numerados. Los nombres `cantidadReservada` y `cantidadVendida` son propuestas de contrato pendientes de confirmación con MS1 y MS3. US-4.1; arquitectura 2.2 y 6.2.2.

#### Scenario: Consultar un resumen mixto
- **WHEN** se consulta disponibilidad de un evento con sectores numerados y no numerados
- **THEN** la respuesta conserva los campos existentes e informa capacidad y cantidades disponibles, reservadas y vendidas por sector

#### Scenario: Disponibilidad cero por reservas temporales
- **WHEN** no quedan lugares disponibles pero existen reservas temporales sin confirmar
- **THEN** las reservas no se contabilizan como ventas confirmadas ni determinan por sí solas el agotamiento comercial

### Requirement: Reserva temporal completa e idempotente
MS2 SHALL crear una reserva completa por diez minutos o rechazarla sin efectos parciales. La clave es `(usuarioId, solicitudId)` y su huella incluye evento y contenido. MS2 SHALL recuperar una reserva idéntica vigente antes de consultar MS1 o MS7, y SHALL rechazar con 409 una clave incompatible o una reserva original no vigente. US-4.1, US-7.4 y US-7.5.

#### Scenario: Crear una reserva nueva
- **WHEN** un cliente autorizado solicita lugares disponibles y cumple las condiciones del evento y del límite
- **THEN** MS2 registra la reserva completa y responde 201 con vencimiento a los diez minutos

#### Scenario: Recuperar una reserva existente durante una caída
- **WHEN** se repite la solicitud idéntica de una reserva vigente y MS1 o MS7 no están disponibles
- **THEN** MS2 devuelve la reserva existente con 200 sin consultar esas dependencias ni modificar el inventario

#### Scenario: Reutilizar una clave incompatible o vencida
- **WHEN** la misma clave se usa con otro evento o contenido, o la reserva original ya no está vigente
- **THEN** MS2 responde 409 sin crear ni modificar una reserva

#### Scenario: Solicitar lugares no disponibles
- **WHEN** un asiento ya está reservado o vendido, o la cantidad no numerada solicitada supera la disponible
- **THEN** MS2 rechaza toda la solicitud sin asignación parcial e informa la cantidad restante para el sector no numerado

### Requirement: Coordinación de reservas con la fila virtual simplificada
Para una reserva nueva MS2 SHALL consultar `requiereFila` a MS1 sin copia local y SHALL exigir un booleano explícito. Si es verdadero SHALL solicitar a MS7 el consumo atómico usando exactamente `usuarioId`, `solicitudId` y huella; si es falso SHALL continuar sin consultar MS7. MS2 SHALL validar respuesta, identidad de evento y solicitud, estado consumido, vencimiento y vigencia, y SHALL fallar de forma cerrada ante una política desconocida o autorización incierta. US-7.4 y US-7.5.

#### Scenario: Evento sin fila
- **WHEN** MS1 confirma que `requiereFila` es falso
- **THEN** MS2 ejecuta el proceso habitual sin consumir un turno

#### Scenario: Evento con fila
- **WHEN** MS1 confirma que requiere fila y MS7 autoriza el consumo vigente
- **THEN** MS2 puede iniciar la creación local, manteniendo controles de evento, inventario y límite

#### Scenario: Política o autorización no verificable
- **WHEN** MS1 o MS7 no responden, o sus respuestas obligatorias son inválidas
- **THEN** MS2 no autoriza una reserva nueva y utiliza el error correspondiente sin asumir una política permisiva

#### Scenario: Reintentar consumo de resultado incierto
- **WHEN** la respuesta del consumo se pierde y se repite la misma solicitud con la misma clave y huella
- **THEN** MS2 no restaura el turno y MS7 recupera el consumo idempotente sin consumir otro turno

#### Scenario: Autorización vence al esperar el bloqueo
- **WHEN** la autorización recibida vence antes de que MS2 adquiera el bloqueo local del evento
- **THEN** MS2 rechaza la reserva nueva aun cuando MS7 la hubiera autorizado anteriormente

### Requirement: Límite de entradas por cliente y evento
MS2 SHALL aplicar el límite positivo del evento a las ventas confirmadas y reservas temporalmente vigentes del cliente más la nueva solicitud. SHALL excluir las unidades de reservas lógicamente vencidas aunque su inventario aún esté retenido, sin descontar anticipadamente los contadores; una devolución aplicada reduce las ventas confirmadas. US-4.2.

#### Scenario: Rechazar superación del límite
- **WHEN** las unidades confirmadas, vigentes y nuevas superan el límite del evento
- **THEN** MS2 rechaza la reserva e indica el límite numérico

#### Scenario: Reserva vencida aún no liberada
- **WHEN** una reserva llegó a `expiraEn` y el scheduler aún no liberó sus lugares
- **THEN** sus unidades no cuentan para el límite, pero sus lugares permanecen retenidos hasta su liberación efectiva

#### Scenario: Solicitudes concurrentes del mismo cliente
- **WHEN** dos solicitudes simultáneas compiten por el límite restante
- **THEN** MS2 acepta solo la combinación que no supera el límite y conserva contadores consistentes

### Requirement: Vencimiento lógico y liberación efectiva
Una reserva SHALL dejar de ser confirmable en `expiraEn` aun cuando persista `VIGENTE`. El scheduler SHALL liberar reservas vencidas de forma automática e idempotente, en transacciones independientes; confirmación, liberación por pago rechazado y expiración SHALL coordinarse sin descontar dos veces. Una consulta de reserva SHALL informar estado persistido y `expiraEn`; `VIGENTE` por sí solo no acredita vigencia temporal. US-4.4.

#### Scenario: Confirmar al llegar al vencimiento
- **WHEN** se intenta confirmar una reserva cuyo `expiraEn` ya llegó
- **THEN** MS2 rechaza la confirmación aunque el scheduler todavía no haya corrido

#### Scenario: Procesar una reserva vencida
- **WHEN** la tarea programada procesa una reserva vencida aún retenida
- **THEN** libera sus lugares y descuenta sus unidades reservadas una sola vez

#### Scenario: Procesamiento concurrente
- **WHEN** vencimiento, confirmación o liberación compiten por una reserva
- **THEN** sus transiciones se serializan y no se libera dos veces ni se libera una reserva confirmada

### Requirement: No doble venta y concurrencia entre instancias
MS2 SHALL garantizar asignaciones completas mediante bloqueos locales, restricciones y actualizaciones condicionales persistentes; SHALL mantener el mismo comportamiento con varias instancias sobre su base. MS2 SHALL NOT realizar HTTP mientras mantiene bloqueos de inventario. US-5.1, US-5.2 y US-5.4; T-20.

#### Scenario: Varios clientes solicitan el mismo asiento
- **WHEN** solicitudes concurrentes de varias instancias compiten por un asiento libre
- **THEN** exactamente una reserva lo obtiene y las demás reciben conflicto

#### Scenario: Competencia por el último lugar general
- **WHEN** varias solicitudes compiten por la última unidad de un sector no numerado
- **THEN** como máximo una la obtiene y la capacidad no se supera

#### Scenario: Reintento transaccional local
- **WHEN** MS2 repite una transacción local tras un conflicto recuperable
- **THEN** vuelve a comprobar idempotencia, estado, autorización y límite sin consumir otro turno dentro de la transacción

### Requirement: Confirmación y devolución idempotentes
MS2 SHALL confirmar una reserva vigente una sola vez y preservar la idempotencia de confirmaciones, liberaciones y devoluciones aprobadas. SHALL validar que las devoluciones correspondan a lugares vendidos de la reserva y no descontar unidades ya devueltas. La coordinación de compras, pagos y reintegros corresponde a MS3 y MS4. US-5.3 y US-4.4.

#### Scenario: Confirmación repetida
- **WHEN** MS3 repite la confirmación de una reserva ya confirmada
- **THEN** MS2 responde 204 sin repetir ventas ni actualizar contadores nuevamente

#### Scenario: Devolución repetida
- **WHEN** se repite una devolución con la misma clave y los mismos detalles
- **THEN** MS2 no libera ni descuenta nuevamente; una clave reutilizada con contenido diferente produce conflicto

### Requirement: Identidad, errores y observabilidad
El Gateway SHALL validar JWT y propagar `X-User-Id` y `X-User-Roles`; MS2 SHALL exigir CLIENTE para crear reservas y aceptar varios roles. SHALL devolver 401 para identidad ausente o inválida y 403 para roles ausentes o insuficientes, precisión pendiente de formalización grupal. Los endpoints internos SHALL permanecer en red confiable sin exigir JWT de usuario. Los errores SHALL conservar `timestamp`, `status`, `error`, `mensaje` y `campos` cuando corresponda. SHALL propagar `X-Correlation-Id`, usar MDC y limpiar el contexto. US-12.1, US-12.2, T-11, T-12 y T-18.

#### Scenario: Cliente con varios roles
- **WHEN** el Gateway propaga una identidad válida con CLIENTE entre varios roles
- **THEN** MS2 permite evaluar la creación bajo ese cliente sin tomar identidad desde el cuerpo

#### Scenario: Identidad o rol ausentes
- **WHEN** falta identidad válida o falta el rol requerido
- **THEN** MS2 responde respectivamente 401 o 403 en el formato de error acordado

#### Scenario: Correlación en solicitudes y errores
- **WHEN** una solicitud válida o fallida atraviesa MS2 y llama sincrónicamente a MS1 o MS7
- **THEN** se propaga la correlación, se incluye en trazas y se limpia el contexto al terminar

### Requirement: Resiliencia y pruebas aisladas
MS2 SHALL usar circuitos independientes para MS1 y MS7, contar como fallos respuestas inválidas y problemas de comunicación, excluir conflictos de negocio definitivos y no reintentar respuestas inválidas ni escrituras automáticamente. Los timeouts predeterminados SHALL ser 2 segundos de conexión y 5 de lectura; solo GET transitorio admite retry limitado. El outbox existente de disponibilidad SHALL conservarse y SHALL NOT ampliarse para la fila. T-13, T-15, T-19 y T-20.

#### Scenario: Respuestas inválidas repetidas
- **WHEN** MS1 o MS7 devuelven repetidamente una respuesta 200 con contenido incompatible
- **THEN** el circuito correspondiente contabiliza fallos y puede abrirse sin reintentar automáticamente el contenido inválido

#### Scenario: Error de negocio remoto
- **WHEN** MS7 responde un conflicto de negocio definitivo
- **THEN** MS2 mantiene el conflicto y no contabiliza esa respuesta como fallo del circuito

#### Scenario: Prueba con dos instancias
- **WHEN** se ejecuta el Compose aislado con dos MS2, dos MS7, bases separadas y MS1 simulado
- **THEN** se verifican reserva, consumo, FIFO, concurrencia, vencimiento y fallos; el resultado no se presenta como validación de MS1 o Gateway reales
