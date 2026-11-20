## ADDED Requirements

### Requirement: Recepción idempotente de acontecimientos
El servicio SHALL exponer `POST /internal/notificaciones`, registrar el aviso de forma persistente antes de responder y devolver una confirmación de recepción sin enviar el correo durante la solicitud. El endpoint SHALL ser de uso interno, exclusivo para servicios autorizados.

#### Scenario: Aviso nuevo
- **WHEN** un servicio envía un aviso válido que no fue recibido antes
- **THEN** el servicio lo guarda en estado PENDIENTE y responde 202 con el identificador de la notificación

#### Scenario: Reentrega del mismo aviso
- **WHEN** llega otra vez un aviso con la misma clave (acontecimiento, tipo de notificación y destinatario)
- **THEN** el servicio no crea una notificación nueva y responde con la confirmación de la ya existente

#### Scenario: Aviso inválido
- **WHEN** falta un campo obligatorio o el tipo de notificación es desconocido
- **THEN** el servicio responde 400 con `{timestamp, status, error, mensaje, campos}` y no guarda nada

#### Scenario: Llamada sin autorización de servicio
- **WHEN** la solicitud no proviene de un servicio autorizado
- **THEN** el servicio responde 403 y no guarda nada

### Requirement: Contrato del acontecimiento
Cada aviso SHALL incluir identificador estable del acontecimiento, tipo, versión de esquema, fecha y hora, identificador del recurso relacionado, identificador del usuario destinatario, identificador de correlación y los datos mínimos para generar el correo.

#### Scenario: Versión de esquema no soportada
- **WHEN** el aviso trae una versión de esquema que el servicio no conoce
- **THEN** el servicio lo rechaza con 400 y el código `ESQUEMA_NO_SOPORTADO`

### Requirement: Tipos de notificación
El servicio SHALL generar el contenido según el tipo: compra confirmada, entradas disponibles, evento cancelado, evento modificado (fecha, horario o lugar), nuevo evento de interés, devolución aprobada, devolución rechazada y resultado de reintegro.

#### Scenario: Contenido según el tipo
- **WHEN** se procesa una notificación de tipo evento modificado
- **THEN** el correo indica el evento y los datos nuevos de fecha, horario o lugar recibidos en el aviso

### Requirement: Envío asíncrono y estados
El servicio SHALL procesar las notificaciones PENDIENTE con una tarea programada, obtener el correo del destinatario en Gestión de usuarios y enviarlo por SMTP. Los estados SHALL ser PENDIENTE, PROCESANDO, ENVIADA y ERROR_FINAL.

#### Scenario: Envío exitoso
- **WHEN** la tarea toma una notificación PENDIENTE y SMTP acepta el correo
- **THEN** la notificación pasa a ENVIADA con fecha de envío

#### Scenario: Selección atómica
- **WHEN** dos instancias de la tarea buscan notificaciones a la vez
- **THEN** cada notificación es tomada por una sola, mediante una actualización condicional de PENDIENTE a PROCESANDO

#### Scenario: Compra no bloqueada
- **WHEN** Gestión de usuarios o SMTP no están disponibles
- **THEN** la recepción de avisos sigue funcionando y la compra o devolución de origen no se ve afectada

### Requirement: Reintentos limitados
El servicio SHALL reintentar los fallos temporales hasta un máximo configurable, registrando intentos, próximo intento y último error, con espera creciente entre intentos.

#### Scenario: Fallo temporal
- **WHEN** SMTP o Gestión de usuarios responden con error temporal
- **THEN** la notificación vuelve a PENDIENTE con intentos + 1 y un próximo intento futuro

#### Scenario: Intentos agotados
- **WHEN** se alcanza el máximo de intentos
- **THEN** la notificación pasa a ERROR_FINAL conservando el último error

#### Scenario: Error definitivo
- **WHEN** el destinatario no existe o no tiene correo
- **THEN** la notificación pasa a ERROR_FINAL sin más reintentos

### Requirement: Recuperación de notificaciones interrumpidas
El servicio SHALL asignar un vencimiento a las notificaciones en PROCESANDO y recuperar las vencidas respetando el máximo de intentos.

#### Scenario: Caída durante el envío
- **WHEN** una notificación sigue en PROCESANDO pasado su vencimiento
- **THEN** una tarea la devuelve a PENDIENTE contando un intento, o a ERROR_FINAL si ya agotó los intentos

#### Scenario: Envío lento que termina después de la recuperación
- **WHEN** el envío de una notificación termina después de que la tarea de recuperación la liberó por vencida
- **THEN** el resultado del envío se guarda con una actualización condicional (estado PROCESANDO y la misma toma), afecta 0 filas y no pisa el estado que dejó la recuperación ni el de una nueva toma; se registra una advertencia

### Requirement: Duplicados en el envío
El servicio SHALL NOT garantizar entrega exactamente una vez.

#### Scenario: Confirmación SMTP perdida
- **WHEN** SMTP acepta el correo pero la confirmación se pierde y se reintenta
- **THEN** el destinatario puede recibir el correo dos veces y el comportamiento queda documentado

### Requirement: Resiliencia hacia servicios externos
El servicio SHALL usar timeouts y circuit breaker (Resilience4j) en las llamadas a Gestión de usuarios y SMTP.

#### Scenario: Circuit breaker abierto
- **WHEN** el circuit breaker hacia Gestión de usuarios está abierto
- **THEN** las notificaciones se posponen como fallo temporal sin llamar al servicio
