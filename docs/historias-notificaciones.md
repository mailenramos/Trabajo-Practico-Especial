# TicketYa — Gestión de notificaciones (User Stories)

Historias del microservicio de Gestión de notificaciones (Entrega 2; numeración MS9 y puerto 8090 a confirmar por el grupo). El detalle técnico está en `openspec/changes/notificaciones/design.md`, los diagramas en [`diagramas-notificaciones.md`](diagramas-notificaciones.md) y el código en [`Backend/mcsv-notificaciones/`](../Backend/mcsv-notificaciones).

## Alcance del servicio

### Qué hace

- Recibe avisos de acontecimientos de otros servicios y los registra sin enviar el correo en la misma solicitud.
- Envía por correo, de forma asíncrona, el aviso al cliente que corresponde.
- Reintenta los envíos fallidos un número limitado de veces y recupera los envíos interrumpidos por una caída.

### Qué no hace

- No tiene endpoints públicos: el cliente no consulta sus notificaciones en este servicio.
- No guarda el correo del cliente: lo pide a Gestión de usuarios en cada envío.
- No decide cuándo ocurre un acontecimiento: cada servicio productor emite su aviso desde su propio outbox.
- No envía SMS ni push, ni maneja preferencias de baja.
- No garantiza entrega exactamente una vez: en casos raros un correo puede llegar dos veces.

**Datos propios:** notificaciones (tipo, estado, destinatario por ID, datos del aviso, intentos, próximo intento, último error y fechas).

| Servicio | Relación con este servicio |
| --- | --- |
| Compras y devoluciones (MS3) | Envía avisos de compra confirmada, entradas disponibles, devoluciones y reintegros; reenvía los de Eventos (cancelación y modificación). |
| Lugares, sectores y eventos (MS1) | Origen de los avisos de evento cancelado o modificado (vía Compras). |
| Preferencias y recomendaciones (MS8) | Envía avisos de nuevo evento de interés. |
| Gestión de usuarios (MS6) | Este servicio le pide el correo del destinatario (`GET /internal/usuarios/{id}/contacto`). |
| Servidor SMTP | Este servicio le entrega los correos (proveedor a definir). |

## Estados de una notificación

| Estado | Significado y transición |
| --- | --- |
| Pendiente | Registrada y esperando su (próximo) intento. Estado inicial; vuelve a él tras un fallo temporal o una recuperación. |
| Procesando | Una instancia de la tarea de envío la tomó. Tiene un vencimiento: si se pasa, la tarea de recuperación la devuelve. |
| Enviada | SMTP aceptó el correo. Estado final. |
| Error final | Destinatario inexistente o sin correo, o se agotaron los intentos. Estado final; conserva el último error. |

## Historias de usuario

### HU-N1 · Enterarme de que mi compra se confirmó

**Como** cliente, **quiero** recibir un correo cuando se confirma mi compra, **para** tener constancia del evento, la cantidad de entradas y el total pagado.

Criterios de aceptación:

- Cuando Compras avisa una compra confirmada, recibo un correo con el evento, la fecha, la cantidad y el total.
- El correo se envía aunque Gestión de usuarios o SMTP estén caídos en el momento de la compra: se reintenta después.
- La compra no se demora ni falla por el envío del correo.

### HU-N2 · Saber que mis entradas están disponibles

**Como** cliente, **quiero** que me avisen cuando mis entradas están emitidas, **para** saber que ya puedo verlas y descargarlas.

Criterios de aceptación:

- Recibo un correo de entradas disponibles con el nombre del evento.

### HU-N3 · Enterarme de que un evento se canceló o cambió

**Como** cliente con entradas, **quiero** recibir un correo si el evento se cancela o cambia de fecha, horario o lugar, **para** reorganizarme o esperar el reintegro.

Criterios de aceptación:

- Si el evento se cancela, el correo lo indica y aclara que el reintegro es automático.
- Si el evento se modifica, el correo muestra solo los datos que cambiaron (fecha, horario o lugar).

### HU-N4 · Conocer el resultado de mi devolución y mi reintegro

**Como** cliente que pidió una devolución, **quiero** saber si fue aprobada o rechazada y si el reintegro se acreditó, **para** no tener que consultar a soporte.

Criterios de aceptación:

- Recibo un correo de devolución aprobada (con el monto) o rechazada (con el motivo).
- Recibo otro correo cuando el reintegro se acredita o falla.

### HU-N5 · Enterarme de eventos que me interesan

**Como** cliente, **quiero** recibir un correo cuando se publica un evento acorde a mis preferencias, **para** conseguir entradas a tiempo.

Criterios de aceptación:

- Cuando Preferencias avisa un nuevo evento de interés, recibo un correo con el evento, la fecha y el lugar.

### HU-N6 · Registrar un aviso sin duplicar (servicio productor)

**Como** servicio productor (Compras, Preferencias), **quiero** registrar un aviso y poder reenviarlo sin que se duplique, **para** que mi worker de outbox pueda reintentar con tranquilidad.

Criterios de aceptación:

- `POST /internal/notificaciones` con un aviso válido responde 202 con el ID de la notificación, sin esperar el envío.
- Si reenvío el mismo aviso (mismo acontecimiento, tipo y destinatario), responde 202 con la misma notificación y `duplicado: true`, y no se crea otra.
- Si falta un campo obligatorio o el tipo es desconocido, responde 400 con `campos` y no se guarda nada.
- Si la versión de esquema no está soportada, responde 400 `ESQUEMA_NO_SOPORTADO`.
- Si la llamada no lleva el token de servicio correcto, responde 403 y no se guarda nada.

### HU-N7 · Que los envíos no se pierdan ni se repitan sin fin (operación)

**Como** responsable de operar la plataforma, **quiero** que los envíos fallidos se reintenten de forma limitada y que los interrumpidos se recuperen, **para** que ningún aviso quede trabado ni se reintente para siempre.

Criterios de aceptación:

- Ante un fallo temporal (SMTP o Gestión de usuarios caídos, timeout, circuit breaker abierto), la notificación vuelve a pendiente con un intento más y un próximo intento cada vez más espaciado.
- Al llegar al máximo de intentos (configurable, 5 por defecto) pasa a error final con el último error.
- Si el destinatario no existe o no tiene correo, pasa a error final sin reintentos.
- Si una instancia se cae con una notificación en procesando, pasado el vencimiento vuelve a pendiente contando el intento (o a error final si ya los agotó).
- Con varias instancias, cada notificación la envía una sola a la vez.

## Contratos

Acordados: `POST /internal/notificaciones` (este servicio) y `GET /internal/usuarios/{id}/contacto` (MS6).

A acordar: forma exacta del aviso y campos de `datos` por tipo (propuesta en el OpenAPI del servicio), forma de la respuesta de MS6 (se asume `{"correo": "..."}`), mecanismo de autorización entre servicios (hoy, token compartido `X-Service-Token`), servidor SMTP y remitente, motor de base de datos y puerto.
