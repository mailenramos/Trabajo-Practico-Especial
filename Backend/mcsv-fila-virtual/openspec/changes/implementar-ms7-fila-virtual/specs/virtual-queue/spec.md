## ADDED Requirements

### Requirement: Fila persistente simplificada por evento
MS7 SHALL administrar como máximo una fila FIFO por evento en su propia base MySQL, sin controlar inventario. Cada fila SHALL conservar estado administrativo, cancelación, cupo y duración; cada turno SHALL conservar usuario, orden persistente, estado y fechas. Los estados de fila son `ACTIVA`, `PAUSADA` y `DESACTIVADA`; los de turno son `ESPERANDO`, `HABILITADO`, `CONSUMIDO` y `VENCIDO`. US-7.1, US-7.3 y US-7.7.

#### Scenario: Crear y recuperar fila del evento
- **WHEN** un administrador u organizador autorizado configura una fila para un evento válido
- **THEN** MS7 mantiene una sola configuración persistente para ese evento

#### Scenario: Concurrencia de creación
- **WHEN** dos solicitudes intentan crear simultáneamente la fila del mismo evento
- **THEN** la persistencia conserva una sola fila y las operaciones se resuelven sin duplicarla

### Requirement: Ingreso a la fila y unicidad de turnos activos
MS7 SHALL identificar al cliente desde la identidad propagada, registrar su ingreso al final del orden persistente y evitar dos turnos activos por cliente y evento incluso entre instancias. Una fila activa SHALL habilitar inmediatamente si corresponde por FIFO y hay cupo; una pausa SHALL permitir recuperar un turno activo pero no crear uno. US-7.1.

#### Scenario: Ingresar con cupo disponible
- **WHEN** un CLIENTE sin turno activo ingresa a una fila activa y le corresponde un cupo
- **THEN** MS7 crea un único turno y lo habilita con vencimiento

#### Scenario: Recuperar turno activo
- **WHEN** un cliente con turno esperando o habilitado vuelve a ingresar, incluso con fila pausada
- **THEN** MS7 devuelve el turno existente sin crear otro

#### Scenario: Ingresar sin turno durante pausa
- **WHEN** un cliente sin turno activo solicita ingresar a una fila pausada
- **THEN** MS7 rechaza el ingreso sin crear turno

#### Scenario: Evento sin fila o terminal
- **WHEN** el evento no requiere fila, la fila está desactivada o el evento está cancelado
- **THEN** MS7 rechaza el ingreso por el motivo correspondiente e indica que se puede reservar directamente si no requiere fila

#### Scenario: Ingresar luego de vencimiento o fallo definitivo
- **WHEN** un cliente cuyo turno venció o cuya reserva falló definitivamente tras el consumo vuelve a una fila activa
- **THEN** obtiene un nuevo turno al final de la cola

#### Scenario: Ingresos concurrentes
- **WHEN** varios clientes ingresan simultáneamente al mismo evento
- **THEN** sus turnos persistidos son únicos y la habilitación respeta el orden registrado

### Requirement: Consulta privada de posición y estado
MS7 SHALL resolver la consulta del turno a partir de la identidad del CLIENTE, sin permitir consultar el turno de otro usuario. Un turno esperando SHALL mostrar posición en la cola; uno habilitado SHALL mostrar su vencimiento; un turno final SHALL informar su estado. US-7.2.

#### Scenario: Consultar turno esperando
- **WHEN** el cliente consulta su turno esperando
- **THEN** obtiene su posición, que disminuye cuando avanzan turnos anteriores

#### Scenario: Consultar turno habilitado o final
- **WHEN** el cliente consulta su turno habilitado, consumido o vencido
- **THEN** recibe su estado y el vencimiento cuando corresponde

#### Scenario: Cliente sin turno
- **WHEN** un cliente sin turno consulta su estado
- **THEN** MS7 responde no encontrado sin revelar turnos ajenos

### Requirement: Habilitación FIFO y control de cupo
MS7 SHALL habilitar por orden FIFO hasta el cupo configurado solo con fila activa y no cancelada. El vencimiento o consumo de un turno SHALL liberar su cupo y permitir habilitar al siguiente; varias instancias SHALL coordinarse mediante persistencia para no superar el cupo al habilitar. US-7.3.

#### Scenario: Se libera un cupo
- **WHEN** un turno habilitado vence o es consumido y hay clientes esperando en una fila activa
- **THEN** MS7 habilita al siguiente por orden persistido hasta completar el cupo

#### Scenario: Cupo completo o reducido
- **WHEN** la cantidad de habilitados alcanza o supera el cupo configurado
- **THEN** MS7 no habilita nuevos turnos hasta que haya capacidad

#### Scenario: Schedulers simultáneos
- **WHEN** dos instancias procesan simultáneamente la misma fila
- **THEN** un turno no se habilita dos veces y no se producen nuevas habilitaciones por encima del cupo

### Requirement: Configuración de cupo y duración
MS7 SHALL permitir al ORGANIZADOR propietario o ADMIN establecer cupo y duración enteros positivos; los valores iniciales SHALL ser 10 clientes y cinco minutos. US-7.6.

#### Scenario: Aumentar cupo
- **WHEN** un autorizado aumenta el cupo de una fila activa
- **THEN** MS7 habilita nuevos clientes esperando por orden FIFO hasta completar el cupo disponible

#### Scenario: Reducir cupo
- **WHEN** un autorizado reduce el cupo por debajo de los habilitados vigentes
- **THEN** MS7 conserva los turnos habilitados y suspende nuevas habilitaciones hasta disponer de cupo

#### Scenario: Modificar duración
- **WHEN** cambia la duración configurada
- **THEN** la nueva duración se aplica a habilitaciones posteriores sin modificar vencimientos ya asignados

#### Scenario: Valores inválidos o altos
- **WHEN** se solicita cupo o duración no positivos, o valores positivos altos compatibles con el contrato
- **THEN** MS7 rechaza los no positivos y acepta los positivos sin cambios de código

### Requirement: Vencimientos y procesamiento automático
MS7 SHALL comprobar vigencia en las operaciones de turno y ejecutar un scheduler configurable mediante `fixedDelay`, predeterminado en 60 segundos. SHALL seleccionar tanto filas con turnos habilitados vencidos como filas activas con clientes esperando y cupo disponible. Cada fila SHALL procesarse en transacción independiente con bloqueo y nueva comprobación de estado. US-7.3 y T-20.

#### Scenario: Turno vencido antes del scheduler
- **WHEN** se intenta consumir un turno cuyo `expiraEn` ya llegó
- **THEN** MS7 no autoriza el consumo aunque el scheduler no haya procesado aún la fila

#### Scenario: Espera con cupo libre sin vencimientos
- **WHEN** el scheduler encuentra una fila activa con clientes esperando y cupo disponible
- **THEN** procesa la fila y habilita por FIFO aunque no haya turnos por vencer

#### Scenario: Error procesando una fila
- **WHEN** falla la transacción de una fila durante un barrido
- **THEN** MS7 registra el error y continúa procesando las demás filas

### Requirement: Consumo atómico e idempotente de un turno
MS7 SHALL exponer `POST /internal/filas/eventos/{eventoId}/consumos` para verificar y consumir atómicamente un turno habilitado y vigente. La solicitud SHALL incluir `usuarioId`, `solicitudId` y `huellaSolicitud`; la respuesta SHALL incluir el consumo, `expiraEn` original y `autorizacionVigente`. La clave `(usuarioId, solicitudId)` SHALL evitar reutilización incompatible incluso entre eventos. US-7.4.

#### Scenario: Consumir un turno habilitado
- **WHEN** MS2 solicita consumir un turno habilitado y vigente
- **THEN** MS7 lo cambia a `CONSUMIDO` una sola vez, devuelve su vencimiento original y libera el cupo

#### Scenario: Repetir consumo idéntico
- **WHEN** llega la misma solicitud y huella nuevamente
- **THEN** MS7 devuelve el consumo histórico sin consumir otro turno

#### Scenario: Solicitudes concurrentes diferentes
- **WHEN** dos solicitudes distintas intentan usar un mismo turno o se reutiliza la clave con evento o huella incompatibles
- **THEN** como máximo una operación consume el turno y la incompatible se rechaza

#### Scenario: Recuperar consumo histórico no vigente
- **WHEN** se recupera un consumo idéntico después de su vencimiento, desactivación o cancelación
- **THEN** se conserva la idempotencia histórica pero no se autoriza una reserva nueva

### Requirement: Fallos parciales sin restauración automática
MS7 y MS2 SHALL conservar la idempotencia cuando se pierde una respuesta de consumo. Un fallo definitivo de reserva SHALL NOT restaurar automáticamente el turno consumido; sin transacción distribuida se acepta que un consumo no produzca reserva. La recuperación de una reserva vigente existente SHALL NOT depender de MS7. US-7.4 y US-7.5.

#### Scenario: Respuesta de consumo perdida
- **WHEN** el cliente reintenta con la misma clave y huella tras una respuesta incierta
- **THEN** se recupera el consumo anterior sin consumir otro turno ni asumir que la primera operación fracasó

#### Scenario: Fallo definitivo posterior al consumo
- **WHEN** la reserva falla definitivamente por falta de inventario o límite
- **THEN** el turno permanece consumido y el cliente puede ingresar de nuevo a la cola activa

#### Scenario: Reserva vigente ya creada
- **WHEN** el cliente repite la solicitud idéntica de una reserva vigente y MS7 no está disponible
- **THEN** MS2 puede devolver la reserva sin volver a consumir turno

### Requirement: Estado administrativo y coordinación con MS1
MS7 SHALL permitir a ORGANIZADOR propietario o ADMIN activar, pausar, reanudar y desactivar la fila, verificando evento y permisos con MS1. Para activar SHALL preparar la configuración, solicitar `requiereFila=true` y activar solo después de la confirmación; para desactivar SHALL cerrar e invalidar turnos activos antes de solicitar `false`. Pausa y reanudación SHALL NOT actualizar la política en MS1. US-7.7.

#### Scenario: Pausar y repetir la pausa
- **WHEN** un autorizado pausa una fila activa o repite la pausa de una fila ya pausada
- **THEN** MS7 no permite nuevos ingresos ni habilitaciones, conserva la vigencia de turnos habilitados y mantiene la política de fila

#### Scenario: Reanudar una fila pausada
- **WHEN** un autorizado reanuda una fila pausada
- **THEN** la fila vuelve a activa sin cambiar `requiereFila` en MS1

#### Scenario: Desactivar y reactivar
- **WHEN** se desactiva una fila y luego se reactiva
- **THEN** los turnos activos invalidados no se reutilizan y los consumos históricos se conservan

#### Scenario: Respuesta administrativa incierta
- **WHEN** falla la comunicación con MS1 durante activación o desactivación
- **THEN** MS7 no informa la operación como completada y permite reintentar la misma operación idempotentemente

#### Scenario: Operaciones opuestas
- **WHEN** existe una operación administrativa con resultado pendiente
- **THEN** se resuelve antes de iniciar otra opuesta, pues el alcance simplificado no garantiza el orden distribuido de solicitudes demoradas o concurrentes

### Requirement: Consulta del estado general
MS7 SHALL permitir al ORGANIZADOR propietario o ADMIN consultar estado, cancelación, cupo, duración, cantidad en espera y cantidad de habilitados vigentes. SHALL verificar la existencia del evento también para ADMIN. US-7.8.

#### Scenario: Consultar como propietario o administrador
- **WHEN** un organizador propietario o administrador consulta una fila existente
- **THEN** recibe el estado general y los conteos acordados

#### Scenario: Consultar sin permisos o sin fila
- **WHEN** consulta un cliente, un organizador no propietario o un evento sin fila registrada
- **THEN** MS7 deniega acceso o responde no encontrado según corresponda

### Requirement: Cancelación de evento idempotente
MS7 SHALL aceptar `PUT /internal/filas/eventos/{eventoId}/cancelacion`, registrar la cancelación incluso sin fila previa e invalidar turnos esperando y habilitados sin modificar consumos históricos. SHALL impedir nuevos ingresos y activaciones posteriores. US-7.9.

#### Scenario: Cancelar evento con turnos
- **WHEN** MS1 notifica la cancelación de un evento con fila
- **THEN** MS7 marca vencidos los turnos activos, conserva consumidos y bloquea reactivaciones

#### Scenario: Cancelación repetida o sin fila
- **WHEN** se repite una notificación o el evento aún no tiene fila registrada
- **THEN** MS7 registra la cancelación de forma idempotente e impide activaciones futuras

### Requirement: Seguridad, observabilidad y resiliencia
El Gateway SHALL validar JWT y propagar identidad y roles; MS7 SHALL exigir CLIENTE para ingreso y consulta de turno, y ORGANIZADOR propietario o ADMIN para administración. Las rutas internas SHALL quedar en red privada. MS7 SHALL mantener formato de errores de Entrega 1, propagar `X-Correlation-Id` y limpiar MDC. El registro HTTP SHALL incluir método, operación, estado, duración y usuario cuando identidad y rol se hayan validado. Resilience4j SHALL aislar consultas y actualizaciones de MS1 mediante circuitos distintos, con retry limitado solo para GET transitorio, sin fallback permisivo ni retry automático de escritura. US-12.1, US-12.2, T-11, T-12, T-15 y T-18.

#### Scenario: Identidad o rol insuficiente
- **WHEN** falta identidad válida o los roles propagados son ausentes o insuficientes
- **THEN** MS7 responde respectivamente 401 o 403 con el formato de error acordado

#### Scenario: MS1 no disponible o respuesta incierta
- **WHEN** MS7 no puede comprobar el evento o confirmar una actualización de política
- **THEN** responde indisponibilidad sin conceder acceso administrativo ni informar éxito de la operación incierta

#### Scenario: Correlación de llamadas
- **WHEN** se recibe un identificador de correlación válido o se genera uno para la solicitud
- **THEN** MS7 lo devuelve, registra y propaga a MS1, y limpia el contexto al finalizar

### Requirement: Despliegue y pruebas individuales aisladas
MS7 SHALL disponer de imagen Docker propia con Java 21, base independiente y configuración por entorno. Las pruebas individuales e integración con MS2 SHALL poder ejecutarse con bases descartables; MS1 simulado y proxy de fallos SHALL NOT presentarse como validación grupal. T-13, T-19, T-20, T-21 y T-22.

#### Scenario: Integración aislada con múltiples instancias
- **WHEN** se ejecuta el Compose de pruebas con dos MS7, dos MS2 y MS1 simulado
- **THEN** se comprueban FIFO, consumo, vencimiento, idempotencia y fallos con bases separadas y recursos descartables

#### Scenario: Validación grupal y carga
- **WHEN** se prepara la validación del sistema completo
- **THEN** se mantienen pendientes las pruebas con MS1 y Gateway reales y el flujo grupal de carga con JMeter
