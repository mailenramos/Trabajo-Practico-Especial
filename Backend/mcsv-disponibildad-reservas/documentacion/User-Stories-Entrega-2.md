# User stories — Entrega 2: MS2 Gestión de disponibilidad y reservas

## Fuentes y alcance

Esta versión consolida las historias atribuidas a MS2 en «TicketYa-Features-y-User-Stories» de la Entrega 1 con los requisitos aplicables de «TicketYa — Features y User Stories (Entrega 2)», la propuesta general de arquitectura y las precisiones acordadas. Cuando una historia comprende varios servicios, se conservan sus criterios y responsabilidades. Las precisiones sujetas a aprobación grupal se identifican expresamente; la redacción propia de F4 para la Entrega 2 aún debe acordarse con el equipo.

## Historias de la Entrega 2

### US-4.1 · Reservar lugares temporalmente

Como cliente, quiero elegir un sector (y asientos específicos si es numerado) y que el sistema me los reserve, para poder pagar sin que otro me los gane.

- Dado un evento publicado con lugares libres, cuando reservo asientos de un sector numerado, entonces se crea una reserva vigente con vencimiento (por defecto 10 minutos), asociada a mi usuario, y esos asientos dejan de estar disponibles para otros clientes.

- Dado un sector no numerado, cuando reservo una cantidad de entradas, entonces el sistema acepta el pedido solo si hay lugares suficientes, considerando tanto las entradas ya vendidas como los lugares retenidos en reservas vigentes de otros clientes; si no alcanzan, lo rechaza completo e indica cuántos lugares quedan.

- Dado que alguno de los asientos pedidos ya está reservado o vendido, cuando reservo, entonces la operación falla por completo con un conflicto y no queda ninguna reserva parcial.

- Dado un evento que no está publicado (borrador, cancelado, finalizado o agotado) o cuya venta está suspendida, cuando intento reservar, entonces el sistema lo rechaza.

- Dado un evento cuya venta se suspende, cuando un cliente ya tenía una reserva vigente, entonces esa reserva se mantiene y puede pagarse hasta su vencimiento.

- Dada una reserva existente que continúa vigente, cuando se repite una solicitud idéntica con la misma clave de idempotencia, entonces se devuelve esa reserva sin crear otra.

- Dada una solicitud con una clave ya utilizada, cuando su contenido es incompatible con la reserva anterior o esta ya no está vigente, entonces se responde con un conflicto y no se crea una nueva reserva.

- Dada una solicitud de reserva nueva, cuando se determina si el evento requiere fila virtual, entonces se aplica la coordinación de política y consumo de turno de US-7.4 y US-7.5; si no puede verificarse una condición obligatoria, no se autoriza la nueva reserva.

- Dado un evento y sus sectores, cuando se consulta el resumen de disponibilidad, entonces se informa por sector la capacidad total, los lugares disponibles, las reservas temporales y los lugares actualmente vendidos, para su utilización por los servicios que correspondan.

- Dado una reserva, cuando se consulta su estado, entonces figura como vigente (no venció y mantiene sus lugares), confirmada (sus lugares fueron vendidos tras aprobarse el pago) o liberada (sus lugares volvieron a estar disponibles).

**Precisión de implementación:** la consulta informa el estado persistido y `expiraEn`. Para determinar la vigencia temporal deben considerarse ambos: una reserva puede permanecer con estado `VIGENTE` después de su vencimiento lógico, hasta que se libere efectivamente. La disponibilidad considera todos los lugares efectivamente retenidos, incluidos los de reservas lógicamente vencidas que aún no fueron liberadas. Esto no modifica su exclusión del límite de US-4.2.

### US-4.2 · Respetar el límite de entradas por cliente

Como organizador, quiero que cada cliente pueda comprar una cantidad máxima de entradas por evento, para evitar acaparamiento y reventa.

- Dado un límite de N entradas por cliente y evento (configurable), cuando un cliente pide una reserva que, sumada a sus entradas vendidas y reservas vigentes de ese evento, supera N, entonces el sistema la rechaza indicando el límite.

- Dado que una reserva del cliente venció o se liberó, o que se aprobó una devolución, cuando pide otra, entonces esas entradas no cuentan para el límite.

- Dado un cliente que envía varias solicitudes de reserva simultáneas para el mismo evento, cuando se procesan, entonces la suma de lo aceptado nunca supera el límite.

- Dado un cliente autenticado, cuando se comprueba el límite de entradas por evento, entonces se utiliza la identidad propagada por el Gateway de acuerdo con US-12.1 y US-12.2.

### US-4.4 · Liberar lugares si el pago falla o la reserva vence

Como cliente, quiero que los lugares se liberen si no concreto la compra, para que otros puedan comprarlos.

- Dado que el servicio de pagos rechaza el pago, cuando el servicio de compras y devoluciones recibe la respuesta, entonces registra la compra como fallida y pide al servicio de disponibilidad y reservas liberar los lugares, que quedan disponibles de inmediato.

- Dada una reserva que llega a su vencimiento sin pago, cuando se alcanza la fecha y hora de vencimiento guardadas, entonces deja de estar vigente y no puede confirmarse, aunque sus lugares pueden seguir retenidos hasta su liberación efectiva.

- Dada una reserva vencida cuyos lugares todavía están retenidos, cuando la tarea programada procesa su vencimiento, entonces libera sus lugares automáticamente.

- Dado varias ejecuciones simultáneas de la liberación automática, cuando se procesan, entonces una misma reserva no se libera dos veces y no se modifica ninguna reserva ya confirmada.

- Dado lugares liberados, cuando otro cliente los busca, entonces aparecen como disponibles.

### US-5.1 · Un asiento, un solo comprador

Como cliente, quiero que si varios intentamos comprar el mismo asiento a la vez solo uno lo obtenga, para que mi entrada sea válida.

- Dado un asiento libre y N clientes que intentan reservarlo simultáneamente, cuando se procesan todos los pedidos, entonces exactamente uno tiene éxito y los N-1 restantes reciben un conflicto.

- Dado el resultado anterior, cuando se consulta el estado del asiento, entonces figura asociado a un único cliente.

### US-5.2 · Último lugar de una zona no numerada

Como cliente, quiero que no se venda más gente que la capacidad de una zona, para no quedarme sin lugar al llegar.

- Dado una zona con 1 lugar disponible y N clientes que piden 1 entrada simultáneamente, cuando se procesan, entonces solo uno tiene éxito y la capacidad vendida nunca supera la máxima.

- Dado una zona con K lugares libres y un pedido de más de K entradas, cuando se procesa, entonces se rechaza completo.

### US-5.3 · Reintentar un pago sin duplicar la compra

Como cliente, quiero poder reintentar el pago si se cortó mi conexión, para no quedar cobrado dos veces ni recibir entradas duplicadas.

- Dado un pedido de pago con una clave de idempotencia (una por operación de pago), cuando se repite con la misma clave, entonces el servicio de compras y devoluciones devuelve el resultado original sin cobrar ni emitir entradas de nuevo.

- Dado un reintento mientras el primer pago sigue en proceso, cuando llega, entonces el sistema no inicia un segundo cobro y responde que la operación está en curso.

- Dado una misma clave con datos distintos a los del primer pedido, cuando llega, entonces el sistema la rechaza.

- Dado un reintento del cobro que llega al servicio de pagos para la misma operación, cuando el pago ya fue resuelto o sigue en proceso, entonces devuelve el resultado original o informa que continúa en proceso, sin cobrar de nuevo.

- Dado una confirmación de venta repetida sobre una reserva ya confirmada, cuando la recibe el servicio de disponibilidad y reservas, entonces no genera duplicaciones ni cambia el estado de la venta.

### US-5.4 · Garantía con varias instancias

Como equipo de desarrollo, queremos que la garantía de no doble venta funcione con varias instancias del mismo microservicio, para poder escalar sin riesgo.

- Dado dos o más instancias del servicio ejecutándose a la vez, cuando reciben pedidos concurrentes sobre el mismo asiento, entonces solo uno se confirma.

- Dado el mecanismo elegido (operaciones atómicas en la base de datos, actualizaciones condicionales del estado de los lugares y restricciones de unicidad), cuando se documenta, entonces queda explicado por qué es correcto sin depender de la memoria de una instancia.

- Dado una prueba automatizada con muchos hilos o procesos sobre el mismo asiento, cuando se ejecuta, entonces verifica que hay un único éxito.

**Nota de trazabilidad de US-5.1 y US-5.4:** las garantías de persistencia vinculan los lugares reservados con la reserva y su cliente, y evitan la doble asignación entre instancias. El resumen de disponibilidad informa lugares disponibles, pero no expone el usuario asociado a cada asiento. La prueba aislada con dos instancias reales de MS2 verificó una reserva exitosa y un conflicto por el mismo asiento; la confirmación idempotente se verifica por separado. La consulta de la asociación por asiento y el flujo completo de compra y confirmación quedan sujetos a la definición de contratos y a la integración grupal, sin eliminar los criterios originales.

## Alcance compartido y precisiones pendientes

La publicación y los cambios de estado del evento corresponden a MS1 (US-2.3 a US-2.5); MS2 inicializa y actualiza su disponibilidad según las operaciones acordadas. Las devoluciones y cancelaciones se coordinan con MS3 y MS1, respectivamente (US-6.2 y US-6.4). Las reglas de fila y de fallos parciales se describen en US-7.4 y US-7.5, sin sustituir el control de inventario de MS2.

Los nombres `cantidadReservada` y `cantidadVendida` para el resumen de US-4.1 son propuestas de contrato pendientes de confirmación con MS1 y MS3. La descripción funcional del resumen está prevista en la propuesta de arquitectura; esta precisión de nombres no constituye un contrato grupal aprobado.

El tratamiento de una lista que combina roles reconocidos y desconocidos permanece pendiente de acuerdo grupal: actualmente MS2 admite la operación si encuentra `CLIENTE`, mientras que MS7 rechaza la lista si contiene roles desconocidos o entradas vacías. Esta observación no establece una regla grupal nueva.

## Requisitos técnicos transversales aplicables

- **US-12.1, US-12.2 y T-11:** el Gateway valida el JWT y propaga `X-User-Id`, `X-User-Roles` y `X-Correlation-Id`; elimina los encabezados de identidad aportados por el cliente. Cada servicio aplica los controles correspondientes a sus operaciones y admite varios roles.
- **Precisión acordada, pendiente de formalización grupal:** en los servicios, una identidad ausente o inválida responde HTTP 401; una identidad válida con roles ausentes o insuficientes responde HTTP 403.
- **T-12:** pruebas de seguridad con token válido, ausente, vencido o inválido, rol insuficiente y acceso a recursos ajenos; los endpoints internos invocados externamente por el Gateway responden 404. Una matriz cubre cada endpoint por rol. La validación del JWT y el enrutamiento del Gateway requieren integración grupal.
- **T-13:** Dockerfile por servicio y Gateway; el despliegue grupal con Compose incluye variables de entorno, red interna, volúmenes persistentes, comprobación de disponibilidad, bases y stack de logs. Solo se expone el Gateway; el replica set de MongoDB corresponde al despliegue grupal.
- **T-14:** cada servicio nuevo tiene una base lógica propia sin acceso compartido. MongoDB para MS1 y MS8 es responsabilidad de esos servicios; la base de MS7 es MySQL según F7.
- **T-15:** el documento grupal establece resiliencia con Resilience4j: tiempos base de 2 segundos de conexión y 5 de lectura, reintentos limitados en operaciones seguras con la misma clave de idempotencia, circuit breaker en integraciones seleccionadas y respuestas alternativas solo en consultas no críticas. Un fallo de comunicación no confirma una operación. MS2 y MS7 incorporan Resilience4j en sus llamadas sincrónicas seleccionadas, mantienen timeouts HTTP configurables y no aplican reintentos automáticos a las escrituras. La interpretación del alcance tecnológico en el sistema completo corresponde al equipo.
- **T-18:** cada servicio registra endpoint, usuario, código de respuesta y tiempo con SLF4J y Logback, y propaga `X-Correlation-Id` entre microservicios sin exponer contraseñas, tokens completos ni datos financieros. Alloy, Loki y Grafana para búsqueda centralizada por servicio y correlación son responsabilidad grupal.
- **T-19:** pruebas de integración de fila y reserva, y de los demás flujos grupales de compra, cancelación y notificaciones, mediante Testcontainers o Docker Compose. El flujo de compra sin fila se ejecutó con MS1, MS2, MS3, MS4, MS5, MS6 y Gateway reales mediante JMeter. Queda por acreditar el flujo grupal con fila activa y notificaciones. La suite aislada MS2–MS7 requiere adaptar su configuración y proxy al token de servicio actual.
- **T-20:** pruebas de ingresos, consumo y habilitación concurrentes en la fila, y de reservas sobre los últimos lugares con al menos dos instancias de MS2 que comparten su base. Como evidencia individual, el Compose aislado ejecutó dos instancias reales de MS2 y dos de MS7, cada par sobre su propia base compartida, para comprobar concurrencia y prevención de sobreventa; MS1 se simuló. La validación del despliegue grupal permanece pendiente.
- **T-21:** prueba de carga grupal del flujo de compra sin fila ejecutada con cinco clientes concurrentes; los resultados, tiempos y errores se encuentran en el [informe JMeter](../../../docs/pruebas-carga/informe.md).
- **T-22:** OpenAPI con seguridad bearer, colección con inicio de sesión, usuarios de prueba por rol, documentación raíz y del Gateway para Compose, diagramas, especificaciones versionadas y limpieza del repositorio; cada servicio mantiene su documentación individual y los elementos comunes son grupales.

T-16 trata los avisos asíncronos de sus servicios de origen; no incorpora outbox a la fila virtual. T-17 asigna la caché de catálogo a MS1 y excluye disponibilidad, reservas, pagos, validaciones QR y autorizaciones. T-23 corresponde al frontend y su integración con el Gateway.

## Trazabilidad de cambios respecto de la Entrega 1

- **US-4.1:** se precisó la recuperación de una reserva vigente y el conflicto ante una solicitud incompatible o reserva no vigente, según las precisiones acordadas y US-7.4 de Entrega 2. Se incorporó una referencia a US-7.4 y US-7.5 para nuevas reservas y fallos parciales. Se agregó el resumen por sector previsto en la propuesta general de arquitectura, secciones 2.2 y 6.2.2; sus nombres de campo siguen pendientes de confirmación grupal.
- **US-4.2:** se incorporó la identificación del cliente propagada por el Gateway conforme a US-12.1 y US-12.2 de Entrega 2. Se conservan los criterios originales del límite.
- **US-4.4:** se distinguió el vencimiento lógico de la liberación efectiva mediante tarea programada, conforme a las precisiones acordadas. Se conservan los criterios originales de pago rechazado, concurrencia y disponibilidad posterior.
- **US-5.1, US-5.2, US-5.3 y US-5.4:** se mantienen los criterios de la Entrega 1, incluidos los correspondientes a compras y pagos. Su aplicación conjunta con la fila se remite a F7; la verificación con múltiples instancias también figura en T-20 de Entrega 2.
- **Requisitos transversales:** se conservan US-12.1, US-12.2 y las historias técnicas aplicables del documento de Entrega 2. Las validaciones grupales de T-15 y T-19 y la formalización del criterio HTTP 401/403 permanecen pendientes de acuerdo con el equipo.
