# User stories — Entrega 2: MS7 Gestión de fila virtual

## Fuentes y alcance

La sección F7 reproduce el documento grupal «TicketYa — Features y User Stories (Entrega 2)»; se corrigieron únicamente errores ortográficos o de formato. MS7 es responsable de la fila; US-7.4 y US-7.5 se coordinan con MS2, y MS1 es autoridad de la política del evento.

## F7 · Fila virtual (Req. 6)

Cuando se abre la venta de un evento masivo, los clientes ingresan a una fila virtual por evento, ven su posición y se los habilita a reservar en orden de llegada, por tandas. La fila regula el acceso a las reservas; no controla el inventario, que sigue siendo responsabilidad de Disponibilidad y reservas. Servicio: Gestión de fila virtual (MS7), que se coordina con Lugares, sectores y eventos (MS1, autoridad sobre si un evento requiere fila) y con Disponibilidad y reservas (MS2).

Alcance simplificado de la Entrega 2: una cola FIFO por evento con turnos en estado en espera, habilitado, consumido y vencido; cupo y duración de turno configurables (por defecto 10 clientes y 5 minutos). Se omiten la preautorización en dos fases, el registro de operaciones de reserva, el outbox, las generaciones de turnos y las políticas versionadas.

### US-7.1 · Ingresar a la fila de un evento

Como cliente, quiero ingresar a la fila virtual de un evento de alta demanda, para obtener un lugar para reservar en orden de llegada.

- Dado un cliente autenticado sin turno activo para un evento con la fila ACTIVA, cuando ingresa a la fila, entonces el sistema registra un turno al final de la cola, con un orden persistente por evento. Si existe cupo disponible y le corresponde según el orden FIFO, se lo habilita inmediatamente; en caso contrario, permanece en espera y se le informa su posición.

- Dado un cliente que ya tiene un turno activo (en espera o habilitado) para ese evento, cuando intenta ingresar de nuevo, entonces no se crea otro turno y se devuelve el existente.

- Dado que muchos clientes ingresan al mismo tiempo, cuando se procesan los pedidos, entonces cada turno recibe un orden único, no se pierden ni se duplican turnos y el orden FIFO respeta el orden de llegada registrado.

- Dado una fila PAUSADA, cuando un cliente sin turno intenta ingresar, entonces el sistema lo rechaza; si ya tenía un turno activo, lo recupera sin generar uno nuevo.

- Dado un evento sin fila, con la fila DESACTIVADA o cancelado, cuando un cliente intenta ingresar, entonces el sistema lo rechaza indicando el motivo (sin fila: puede reservar directamente).

- Dado un turno vencido, o consumido cuya reserva falló definitivamente, cuando el cliente vuelve a ingresar con la fila ACTIVA, entonces obtiene un turno nuevo al final de la cola.

- Dado un pedido sin token o de un usuario que no es cliente, cuando intenta ingresar, entonces el acceso es denegado.

### US-7.2 · Consultar mi turno

Como cliente, quiero consultar la posición, el estado y el vencimiento de mi turno, para saber cuándo me toca y cuánto tiempo tengo para reservar.

- Dado un turno en espera, cuando lo consulto, entonces veo mi posición, que disminuye a medida que avanza la cola.

- Dado un turno habilitado, cuando lo consulto, entonces veo la fecha y hora de vencimiento y puedo continuar con la reserva.

- Dado un turno vencido o consumido, cuando lo consulto, entonces veo el estado final y, si corresponde, puedo volver a ingresar.

- Dado un cliente sin turno para ese evento, cuando consulta, entonces recibe un error de «no encontrado».

- Dado el turno de otro cliente, cuando intento consultarlo, entonces no es posible: la consulta siempre se resuelve con el usuario del token.

### US-7.3 · Habilitar clientes por tandas en orden FIFO

Como cliente en espera, quiero que me habiliten según mi orden de llegada, para comprar de forma justa y sin competir con toda la cola.

- Dado una fila ACTIVA con cupo C y menos de C turnos habilitados, cuando hay clientes en espera, entonces se habilita al siguiente en orden FIFO hasta completar el cupo y se registra el vencimiento del turno (por defecto 5 minutos).

- Dado el cupo de turnos habilitados completo, cuando hay más clientes en espera, entonces nadie más se habilita hasta que exista cupo disponible.

- Dado un turno habilitado que llega a su vencimiento sin consumirse, cuando pasa el tiempo límite, entonces pasa a vencido, libera su lugar en el cupo y, si la fila está ACTIVA y existe cupo disponible, se habilita al siguiente en espera.

- Dado un turno que se consume, cuando se registra el consumo, entonces deja de ocupar cupo habilitado y, si la fila está ACTIVA y existe cupo disponible, se habilita al siguiente en espera.

- Dado varias ejecuciones simultáneas de la habilitación, incluso con varias instancias del servicio, cuando se procesan, entonces un mismo turno no se habilita dos veces y no se realizan nuevas habilitaciones si se alcanzó o superó el cupo configurado.

### US-7.4 · Reservar consumiendo el turno

Como cliente habilitado, quiero crear mi reserva usando mi turno, para acceder al proceso de reserva respetando el orden de la fila virtual.

- Dado un evento cuyo indicador requiereFila (consultado en ese momento a Lugares, sectores y eventos, sin copia local) es verdadero, cuando un cliente pide una reserva, entonces Disponibilidad y reservas pide a la fila verificar y consumir el turno antes de crearla, enviando usuarioId, solicitudId y la huella de la solicitud.

- Dado un cliente sin turno habilitado y vigente, cuando intenta reservar, entonces la reserva se rechaza indicando que debe ingresar a la fila o esperar su turno.

- Dado un turno habilitado y vigente, cuando se consume, entonces la verificación y el consumo son una única operación atómica: el turno pasa a consumido y se devuelve el consumo, la fecha de vencimiento original (expiraEn) y el indicador autorizacionVigente.

- Dado una repetición idéntica del pedido (mismo solicitudId y misma huella), cuando llega a la fila, entonces es idempotente: no consume otro turno y devuelve el consumo ya registrado.

- Dado un turno ya consumido por una operación de reserva, cuando llega una solicitud diferente, entonces no puede reutilizarse ese turno para autorizar una nueva operación.

- Dado dos pedidos simultáneos que intentan consumir el mismo turno, cuando se procesan, entonces el turno se consume una única vez. Si los pedidos son idénticos, se devuelve el mismo consumo de forma idempotente.

- Dado un consumo anterior, cuando se reintenta la misma solicitud, entonces la fila verifica que no haya vencido el plazo original del turno, que la fila no esté desactivada y que el evento no esté cancelado. Disponibilidad y reservas comprueba nuevamente el vencimiento de la autorización antes de crear una nueva reserva.

- Dado una reserva vigente ya creada, cuando el cliente recupera su resultado con la misma clave de idempotencia, entonces se devuelve sin consumir un turno y sin depender de la vigencia del turno original ni de la disponibilidad de la fila.

- Dado un evento cuyo indicador requiereFila es falso, cuando un cliente reserva, entonces se aplica el proceso habitual de reservas, sin consultar a la fila.

- Dado un consumo autorizado, cuando Disponibilidad y reservas inicia la creación de una nueva reserva, entonces verifica nuevamente el vencimiento de la autorización después de adquirir el bloqueo local del evento y aplica los controles de inventario, estado de venta y límites por cliente, sin realizar llamadas a otros microservicios mientras mantiene los bloqueos de inventario.

Limitación aceptada para esta entrega: debido a la ausencia de transacciones distribuidas, una desactivación o cancelación concurrente posterior a la validación de la autorización en MS7 puede no reflejarse inmediatamente en MS2. Se mantienen los controles locales del estado del evento, la vigencia de la autorización y el inventario.

### US-7.5 · Fallos parciales y fila no disponible

Como cliente, quiero que el sistema se comporte de forma segura si algo falla durante la reserva, para evitar reservas no autorizadas y poder reintentar solicitudes cuyo resultado sea incierto.

- Dado que no puede verificarse la política del evento en Lugares, sectores y eventos o, cuando corresponda, la autorización del turno en la fila virtual, cuando se solicita una nueva reserva, entonces Disponibilidad y reservas rechaza temporalmente la operación; una configuración desconocida o una falla de comunicación nunca se interpreta como permiso para omitir la fila.

- Dado un turno consumido cuya reserva falla definitivamente (por ejemplo, sin inventario o por el límite de compra), cuando el cliente quiere volver a intentar, entonces el turno no se restaura y el cliente puede volver a ingresar a la fila.

- Dado un fallo de comunicación sin respuesta del consumo, cuando el cliente reintenta la misma solicitud, entonces se repite de forma idempotente, sin consumir otro turno ni dar por fallida la operación.

- Dado que no hay transacciones distribuidas entre Disponibilidad y reservas y la fila, cuando un turno se consume y la reserva no llega a crearse por un fallo, entonces queda documentado como limitación aceptada y el cliente puede volver a ingresar a la fila.

- Dado que la fila no está disponible, cuando un cliente solicita recuperar el resultado de una reserva vigente ya creada con la misma clave de idempotencia, entonces puede recuperarla sin depender de la fila.

### US-7.6 · Configurar el cupo y la duración de los turnos

Como organizador propietario del evento (o administrador), quiero configurar el cupo de clientes habilitados y la duración de cada turno, para adaptar la fila a la demanda y a la capacidad de venta.

- Dado un organizador propietario o un administrador, cuando configura un cupo y una duración mayores a 0, entonces los valores quedan guardados para ese evento (por defecto, 10 clientes y 5 minutos).

- Dado un cupo o una duración menores o iguales a 0, cuando intenta configurarlos, entonces recibe un error de validación.

- Dado un organizador que no es propietario del evento, o un cliente, cuando intenta configurar la fila, entonces el acceso es denegado.

- Dado un aumento de cupo en una fila ACTIVA, cuando se aplica, entonces se habilitan nuevos clientes respetando el orden FIFO hasta completar el cupo disponible.

- Dado una reducción de cupo, cuando se aplica, entonces no se revocan los turnos ya habilitados y se suspenden las nuevas habilitaciones hasta quedar por debajo del nuevo límite.

- Dado un cambio en la duración de los turnos, cuando se aplica, entonces rige solo para las nuevas habilitaciones.

- Dado valores mayores, por ejemplo para las pruebas de carga, cuando se configuran, entonces se aceptan sin cambios de código.

### US-7.7 · Activar, pausar y desactivar la fila

Como organizador propietario del evento (o administrador), quiero activar, pausar o desactivar la fila de mi evento, para controlar el acceso a la venta según la situación.

- Dado un evento no cancelado con la fila sin activar, cuando se activa la fila, entonces la fila prepara su configuración, Lugares, sectores y eventos establece requiereFila en verdadero y, solo una vez confirmado, la fila queda ACTIVA.

- Dado una fila ACTIVA, cuando se pausa, entonces no se aceptan nuevos ingresos ni habilitaciones, los turnos ya habilitados conservan su vigencia y pueden consumirse, los clientes recuperan su turno existente y requiereFila sigue en verdadero.

- Dado una fila ya PAUSADA, cuando se pide pausarla de nuevo, entonces la operación es idempotente; solo puede pausarse una fila activa.

- Dado una fila PAUSADA, cuando se reanuda, entonces vuelve a ACTIVA y requiereFila no cambia.

- Dado una fila activa o pausada, cuando se desactiva, entonces los turnos en espera y habilitados pasan a vencido, Lugares, sectores y eventos establece requiereFila en falso y Disponibilidad y reservas permite reservar directamente.

- Dado una fila DESACTIVADA, cuando se reactiva, entonces los turnos en espera y habilitados que fueron invalidados no se reutilizan. Los consumos históricos se conservan para garantizar la idempotencia.

- Dado un fallo de comunicación con Lugares, sectores y eventos durante la activación o desactivación, cuando se intenta el cambio, entonces no se informa como completado y puede reintentarse de forma idempotente.

- Dado un evento cancelado, cuando se intenta activar la fila, entonces Gestión de fila virtual rechaza la activación a partir del estado del evento informado por Lugares, sectores y eventos o de la cancelación registrada localmente.

- Limitación aceptada para esta entrega: la coordinación entre servicios no garantiza el orden de las solicitudes administrativas opuestas, concurrentes o demoradas. Se deberá resolver una operación administrativa pendiente antes de iniciar otra opuesta.

### US-7.8 · Consultar el estado general de la fila

Como organizador propietario del evento (o administrador), quiero consultar el estado general de la fila de mi evento, para seguir la venta durante la apertura.

- Dado una fila existente, cuando el organizador propietario o un administrador la consulta, entonces ve el estado (ACTIVA, PAUSADA o DESACTIVADA), el indicador de cancelación, el cupo, la duración de los turnos, la cantidad de turnos en espera y la cantidad de turnos habilitados vigentes.

- Dado un organizador que no es propietario del evento, o un cliente, cuando intenta consultarla, entonces el acceso es denegado.

- Dado un evento sin fila registrada, cuando se consulta, entonces recibe un error de «no encontrado».

### US-7.9 · Invalidar la fila ante la cancelación del evento

Como cliente, quiero que mi turno deje de tener efecto si el evento se cancela, para no reservar lugares para un evento que ya no se realiza.

- Dado un evento que se cancela, cuando Lugares, sectores y eventos notifica a la fila (PUT /internal/filas/eventos/{eventoId}/cancelacion), entonces los turnos en espera y habilitados pasan a vencido y los turnos consumidos no se modifican.

- Dado un evento cancelado con fila registrada, cuando un cliente intenta ingresar o se intenta reactivar la fila, entonces el sistema lo impide.

- Dado la misma notificación de cancelación recibida más de una vez, cuando se procesa, entonces es idempotente y no produce cambios adicionales.

- Dado un evento cancelado que no tiene una fila registrada, cuando se recibe la notificación de cancelación, entonces se registra la cancelación para impedir futuras activaciones de la fila virtual de ese evento.

### Decisiones de implementación:

- Se utiliza MySQL como motor de base de datos de MS7.

- El consumo de un turno libera su lugar en el cupo, independientemente de que la reserva llegue a crearse.

- Los vencimientos se resuelven mediante una tarea programada y comprobaciones adicionales de vigencia.

- La coordinación entre servicios no garantiza el orden de solicitudes administrativas concurrentes o demoradas, limitación aceptada en la propuesta.

### Evolución futura (fuera de alcance)

preautorización en dos fases con registro de operaciones de reserva en MS2, outbox de avisos, generaciones de turnos, políticas versionadas, y los estados preautorizado y cancelado del turno.

## Requisitos técnicos transversales aplicables

- **US-12.1, US-12.2 y T-11:** el Gateway valida el JWT y propaga identidad y roles; cada servicio aplica los controles correspondientes a sus operaciones. El Gateway debe eliminar encabezados de identidad aportados por el cliente.
- **Precisión acordada, pendiente de formalización grupal:** en los servicios, una identidad ausente o inválida responde HTTP 401; una identidad válida con roles ausentes o insuficientes responde HTTP 403.
- **T-12:** pruebas de seguridad por rol, titularidad, token y protección de rutas internas; la validación de JWT y el 404 de rutas internas externas requieren el Gateway.
- **T-13:** Dockerfile y configuración de cada servicio; Compose, red interna y exposición exclusiva del Gateway son responsabilidades grupales.
- **T-15:** el documento grupal establece resiliencia con Resilience4j: tiempos base de 2 segundos de conexión y 5 de lectura, reintentos limitados en operaciones seguras con la misma clave de idempotencia, circuit breaker en integraciones seleccionadas y respuestas alternativas solo en consultas no críticas. Un fallo de comunicación no confirma una operación. MS2 y MS7 incorporan Resilience4j en sus llamadas sincrónicas seleccionadas, mantienen timeouts HTTP configurables y no aplican reintentos automáticos a las escrituras. La interpretación del alcance tecnológico en el sistema completo corresponde al equipo.
- **T-18:** cada servicio registra método o endpoint, usuario cuando corresponde, código de respuesta y tiempo con SLF4J y Logback, y propaga `X-Correlation-Id` entre microservicios sin exponer contraseñas, tokens completos ni datos financieros. La centralización con Alloy, Loki y Grafana corresponde a la integración grupal.
- **T-19:** pruebas de integración de fila y reserva, y de los demás flujos grupales de compra, cancelación y notificaciones, mediante Testcontainers o Docker Compose. Existe un Compose aislado con MS2 y MS7 reales y MS1 simulado; los flujos que requieren MS1, MS3, MS6 y Gateway reales permanecen pendientes de integración grupal.
- **T-20:** pruebas de ingresos, consumo y habilitación concurrentes en la fila, y de reservas sobre los últimos lugares con al menos dos instancias de MS2 que comparten su base. Como evidencia individual, el Compose aislado ejecutó dos instancias reales de MS2 y dos de MS7, cada par sobre su propia base compartida, para comprobar concurrencia y prevención de sobreventa; MS1 se simuló. La validación del despliegue grupal permanece pendiente.
- **T-21:** prueba de carga grupal del flujo completo con JMeter e informe; configurar valores altos no sustituye esta prueba.
- **T-22:** documentación individual y contratos actualizados; datos comunes, documentación raíz y despliegue integrado son grupales.

T-16 trata los avisos asíncronos de sus servicios de origen; no incorpora outbox a la fila virtual. T-17 asigna la caché de catálogo a MS1 y excluye disponibilidad, reservas y autorizaciones. T-23 corresponde al frontend y su integración con el Gateway.

## Precisiones y decisiones pendientes

El tratamiento de una lista que combina roles reconocidos y desconocidos permanece pendiente de acuerdo grupal: actualmente MS2 admite la operación si encuentra `CLIENTE`, mientras que MS7 rechaza la lista si contiene roles desconocidos o entradas vacías. Esta observación no establece una regla grupal nueva.

Los nombres `cantidadReservada` y `cantidadVendida` del resumen de MS2 son propuestas de contrato pendientes de confirmación con MS1 y MS3. La fila virtual no controla el inventario ni determina esos campos.
