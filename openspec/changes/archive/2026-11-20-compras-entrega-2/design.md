## Contexto

MS3 ya maneja compras, devoluciones, reintegros y cancelaciones con un patrón de recuperación por Spring Scheduler (`ReanudacionDeCancelaciones`). Se reutiliza ese estilo para los avisos.

## Decisiones

- **Autorización interna:** filtro que exige `X-Service-Token` solo en `/internal/**` nuevas (las existentes de cancelación quedan como están). Comparación en tiempo constante; sin secreto configurado, rechaza todo. Mismo mecanismo que Notificaciones; provisorio hasta el JWT.
- **`tipoEvento` en `Compra`:** columna nullable `tipo_evento`, copiada de Eventos al iniciar la compra. Con `ddl-auto=update` se agrega sola; las compras viejas quedan en nulo y se ignoran en el historial. No hay migración de datos.
- **Consultas:**
  - Historial: `select c.tipoEvento, count(c) from Compra c where c.clienteId = :id and c.estado = CONFIRMADA and c.tipoEvento is not null group by c.tipoEvento order by count(c) desc`.
  - Eventos comprados: `eventoId` distintos de compras CONFIRMADAS del cliente, ordenados por la fecha de compra más reciente de cada evento, límite 20.
  - Co-compras: sobre clientes distintos con compra confirmada del evento, contar clientes distintos por otro evento, orden descendente, límite.
- **Outbox:** tabla `avisos_pendientes(id, acontecimiento_id, tipo, usuario_id, recurso_id, correlacion_id, datos JSON, estado, intentos, proximo_intento, ultimo_error, creado_en, toma, vence_en, enviado_en)`. `correlacion_id` es un UUID por aviso que se manda como `correlacionId` (obligatorio en el contrato de Notificaciones); `toma` identifica la ejecución que tomó el aviso y `vence_en` hasta cuándo puede tenerlo en PROCESANDO (los dos vuelven a nulo al terminar); `enviado_en` es el momento en que Notificaciones confirmó la recepción. Se inserta en la transacción de cada cambio de estado. La restricción única es compuesta: (`acontecimiento_id`, `tipo`, `usuario_id`), la misma clave con la que Notificaciones deduplica. Así los avisos de cancelación y modificación (uno por comprador) comparten el `acontecimiento_id` y no chocan entre sí, y un reintento del mismo comprador sí choca. El alta es un INSERT nativo con `on duplicate key update id = id`: el choque no crea otra fila ni lanza error, así que la transacción de negocio no queda marcada para revertirse. `acontecimiento_id` se arma con tipo + id del recurso, sin el id del cliente, y no pasa los 100 caracteres que acepta Notificaciones: `evento-modificado-` (18) + `eventoId` (hasta 64) + `-v` + versión (hasta 10 dígitos) = 94 como máximo; `evento-cancelado-` + `eventoId` = 81 como máximo; compra, entradas, devolución y reintegro usan UUID de 36 y quedan en unos 60.
- **Envío:** `@Scheduled` toma avisos PENDIENTE con actualización condicional a PROCESANDO (guarda `toma` y `vence_en`), llama a `POST /internal/notificaciones` con `X-Service-Token`, y marca ENVIADO o reprograma con espera creciente (30 s, 60 s, 120 s..., tope 30 min). Máximo de intentos configurable (5 por defecto). El resultado se guarda con una actualización condicional sobre la misma `toma`.
  - **Qué se reintenta:** una respuesta 400, 415 o 422 de Notificaciones es un rechazo definitivo (el aviso es inválido y repetirlo no sirve): pasa a ERROR_FINAL sin más intentos. Cualquier otro error se reintenta, incluido el 403 (suele ser un token mal configurado, que se corrige sin tocar el aviso), y también la falta de respuesta.
  - **Recuperación:** los PROCESANDO con `vence_en` pasado (2 min por defecto) vuelven a PENDIENTE contando el intento, o a ERROR_FINAL si agotaron los intentos. Como se borra la `toma`, el resultado tardío de un envío lento no pisa el estado.
- **Atomicidad e idempotencia:** el aviso se guarda con la operación (todo o nada); el envío es de al menos una vez y Notificaciones deduplica por (acontecimiento, tipo, destinatario). En los reintegros, la llamada a Pagos se hace antes y fuera de la transacción que abre `ReintegroService`; esa transacción cubre solo guardar el resultado y su aviso RESULTADO_REINTEGRO (si quien llama ya tiene una transacción, se suma a ella).
- **Cierre de solicitudes por cancelación:** cuando la cancelación de un evento cierra como rechazadas las solicitudes de devolución pendientes, no se genera DEVOLUCION_RECHAZADA: el cliente ya recibe EVENTO_CANCELADO y su reintegro automático. Ese aviso solo sale cuando un administrador rechaza la solicitud.
- **Contrato del aviso:** el de `Backend/mcsv-notificaciones/.../openapi.yaml`. Campos de `datos` por tipo: COMPRA_CONFIRMADA (evento, fecha, cantidad, total), ENTRADAS_DISPONIBLES (evento, fecha), EVENTO_CANCELADO (evento, fecha), DEVOLUCION_APROBADA (evento, monto), DEVOLUCION_RECHAZADA (evento, motivo), RESULTADO_REINTEGRO (monto, resultado EXITOSO o FALLIDO).

## Contratos con otros servicios

Acordados: `POST /internal/notificaciones` (MS9, implementado).
Contrato a acordar:
- Que MS1 devuelva `tipo` en `GET /eventos/{eventoId}`.
- Nombre del encabezado de token y variable de entorno, a confirmar con el grupo (hoy `X-Service-Token` y `SERVICIO_TOKEN`).
- URL de MS9 en el contenedor (`notificaciones:8090`, a confirmar).
- Contador `version` y formato de `fechaHora` de MS1 en `eventos-modificados`.
- Si MS8 necesita paginación o un límite distinto en co-compras.

## Modificación de eventos

- **Endpoint:** `POST /internal/eventos-modificados`, con `X-Service-Token`, misma forma que `/eventos-cancelados`.
- **Body:** `eventoId` y `version` (entero >= 1, lo incrementa Eventos en cada modificación) obligatorios; `fechaHora`, `lugar` y `ciudad` opcionales, con al menos uno.
- **Deduplicación:** tabla `modificaciones_evento(id, evento_id, version, recibida_en)` con restricción única (`evento_id`, `version`). Si ya existe, 200 sin tocar nada. Si la versión es menor que la máxima ya procesada del evento, también 200 y se ignora.
- **Atomicidad:** en una sola transacción se inserta la modificación, se actualiza `eventoFechaInicio` en `Compra` y en `SolicitudDevolucion` del evento (UPDATE masivo, solo si viene `fechaHora`) y se insertan los avisos EVENTO_MODIFICADO en el outbox. Si algo falla, no queda nada; el reintento de Eventos lo vuelve a intentar con la misma versión.
- **Compradores afectados:** clientes distintos con compra CONFIRMADA del evento. Un aviso por cliente.
- **Aviso:** `acontecimientoId = evento-modificado-{eventoId}-v{version}` (Notificaciones deduplica además por destinatario); `recursoId = eventoId`; `datos`: `evento` (nombre guardado en la compra), `nuevaFecha` (dd/MM/yyyy), `nuevoHorario` (HH:mm), `nuevoLugar` (lugar y ciudad). Fecha y horario se arman en la zona horaria configurada (por defecto America/Argentina/Buenos_Aires) y solo se incluyen si vino `fechaHora`.
- **Respuestas:** 202 nuevo, 200 ya procesado o versión anterior, 400 cuerpo inválido, 403 token.
- **Sin integración con Pagos ni Entradas:** modificar fecha o lugar no genera reintegros ni invalida entradas.

Contrato a acordar con Eventos: que tenga un contador `version` por evento (si no, usar la fecha de modificación como identificador) y que mande `fechaHora` en UTC.

## Riesgos

- Compras viejas sin tipo reducen la calidad inicial de las recomendaciones.
- El envío de avisos es al menos una vez; un reintento tras una confirmación perdida puede repetir el correo (aceptado).
