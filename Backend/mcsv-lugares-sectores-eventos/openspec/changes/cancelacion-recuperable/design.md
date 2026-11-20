# Diseño: cancelación recuperable

## Flujo y ventanas de fallo actuales

1. `EventoLifecycleService` valida ADMIN, evento y estado. Todavía no hay intención durable; un corte no deja trabajo.
2. Se llama a `PUT /disponibilidad/eventos/{eventoId}/venta` de Reservas con `CANCELADA`. Si falla o vence el timeout, MS1 devuelve `503` y no guarda cancelación ni aviso. Reservas podría haber aplicado el cambio aunque la respuesta se perdiera. Si confirma `204` y MS1 se corta antes del siguiente commit, la venta queda cerrada pero el evento sigue publicado o agotado, sin aviso a Compras ni recuperación automática.
3. La transacción MongoDB guarda el evento cancelado y `cancelacion-{eventoId}` para Compras. Un rollback no deja ninguno, aunque Reservas ya haya cerrado la venta. Tras el commit se invalida la caché; un corte antes del envío deja el aviso pendiente.
4. El dispatcher intenta `POST /eventos-cancelados` a Compras fuera de la transacción. Timeout o `5xx` se reintentan; errores definitivos o agotamiento terminan en `ERROR_FINAL`. Si Compras aceptó pero MS1 no registró el resultado, el lease permite reenviar con el mismo ID. Un `2xx` confirma recepción, no que finalizaron reintegros. Hoy no existe aviso a MS7.

## Decisión: intención durable y recuperación

Antes de llamar a Reservas, una transacción local marca en el evento una intención interna de cancelación y crea un trabajo outbox con ID `cancelacion-reservas-{eventoId}`, motivo efectivo, correlación y datos necesarios para reintentar. No agrega un estado formal ni expone la marca en la respuesta pública; el evento conserva `publicado` o `agotado` hasta la confirmación remota. La primera intención fija el motivo: solicitudes repetidas o concurrentes no lo reemplazan ni crean otro trabajo.

El dispatcher reclama ese trabajo con las mismas reglas de exclusión, lease, backoff y máximo de intentos; lo intenta inmediatamente tras el commit y también desde el scheduler. Usa el contrato existente de Reservas (`PUT` con `{"estado":"CANCELADA"}`, timeouts configurados de Reservas, por defecto conexión 2 s y lectura 5 s; solo `204` confirma). La llamada ocurre fuera de cualquier transacción MongoDB. El contrato existente debe seguir aceptando repetir el mismo estado con `204`, incluso cuando se perdió la respuesta anterior. Un `2xx` distinto de `204` no confirma el cierre y se trata como reintentable; los errores HTTP siguen la política de outbox, con `3xx` y `4xx` definitivos salvo `408`/`429`.

Tras `204`, una única transacción MongoDB cancela el evento, registra `canceladoEn` y el motivo, marca entregado el trabajo de Reservas y crea los avisos estables `cancelacion-{eventoId}` para Compras y `cancelacion-fila-{eventoId}` para MS7. Si la transacción falla o el proceso se interrumpe antes del commit, el lease permite reintentar la operación idempotente en Reservas; no se crean avisos huérfanos. Tras el commit, la invalidación centralizada ocurre después del commit y los dos avisos se procesan independientemente, de inmediato o por scheduler. Un error posterior de cualquiera no revierte la cancelación ni el otro aviso.

Como la caché es local, un corte posterior al commit pero anterior a su invalidación puede dejar resultados viejos hasta el TTL; las demás instancias también pueden servir datos viejos hasta el vencimiento, como ya ocurre con la caché existente.

La confirmación transaccional se condiciona al reclamo vigente del trabajo: al actualizarlo, comprobar atómicamente el ID, `PROCESANDO`, el `leaseHasta` reclamado aún vigente y el valor de `intentos` devuelto al reclamar. `intentos` ya se incrementa en cada reclamo y, junto con el lease, distingue un reclamo posterior incluso si las fechas coinciden; no hace falta una generación adicional. La actualización condicionada ocurre dentro de la transacción y, si no coincide, se revierte sin cancelar ni crear avisos. Los IDs estables y la versión del evento refuerzan la deduplicación.

Con una intención pendiente, suspender y reactivar responden `409`; la finalización programada ignora el evento y vuelve a considerarlo en la siguiente pasada. Si Reservas responde `409` al suspender o reactivar, MS1 devuelve `409` sin modificar el evento; otros fallos conservan `503`. Cancelar nuevamente responde `503` mientras esté pendiente y `409` una vez cancelado. Un callback de disponibilidad no debe cambiar la intención ni impedir completarla; las escrituras locales concurrentes usan el control de versión del evento. No se agrega un lease compartido ni coordinación adicional entre operaciones de venta.

**Contrato confirmado con Reservas:** repetir `CANCELADA` sobre un evento ya cancelado responde `204`; `CANCELADA` es terminal y los intentos posteriores de pasar a `ACTIVA` o `SUSPENDIDA` se rechazan con `409`. Las guardas no detienen una llamada que ya estaba en curso cuando se registró la intención.

En el primer intento, si Reservas confirma y se confirma la transacción local, responder `200` con el evento como hoy, aunque falten los avisos. Si Reservas falla, no confirma o el trabajo está en curso, responder `503`: la intención durable permanece y el scheduler la recupera, por lo que `503` ya no significa ausencia de efectos futuros. Si ya está cancelado, mantener `409` y no cambiar datos ni duplicar avisos: la operación es idempotente en efectos aunque la respuesta repetida no sea `200`. Evento finalizado sigue respondiendo `409`; autenticación y demás códigos se mantienen.

La recuperación automática no es infinita: timeout, transporte, `5xx`, `408` y `429` se reintentan hasta 8 reclamos; `ERROR_FINAL` no se reintenta automáticamente y requiere revisión operativa. Esto incluye un corte en el último reclamo, incluso si Reservas ya aplicó el cambio. Sin `SERVICIO_TOKEN`, el comportamiento existente pausa el dispatcher sin reclamar ni consumir intentos; documentar que la intención espera. No se borra ni revierte una confirmación remota.

## Aviso a Fila virtual y compatibilidad

Crear el aviso para MS7 en la transacción de cancelación, independientemente de `requiereFila`: `PUT {FILAS_BASE_URL}/internal/filas/eventos/{eventoId}/cancelacion`, sin body ni `Content-Type` de JSON innecesario. MS7 responde `204` con fila, sin fila o al repetir; el dispatcher considera cualquier `2xx` entregado. Se envían los headers comunes `X-Aviso-Id`, `X-Service-Token` y `X-Correlation-Id`; MS7 no los exige como condición del contrato. Timeout y `5xx` reintentan, `400` es definitivo; se conserva el resto de la clasificación general.

Agregar método HTTP opcional al aviso y admitir payload ausente. Los avisos antiguos sin método conservan `POST` y su payload actual; publicación y Compras siguen enviando el mismo body y headers. No retroalimentar `requiereFila` desde este aviso ni crear backfill para cancelaciones históricas.

`FILAS_BASE_URL` usa `http://localhost:8088` en host y `http://fila-virtual:8088` en Compose, configurable por entorno. Nunca se persiste ni se registra el token de servicio.

## Backfill existente

`EventoCancelacionOutboxBackfill` solo migra documentos con `avisoCancelacionPendiente=true` y estado `CANCELADO`, motivo y fecha válidos. Una intención nueva pendiente conserva estado `PUBLICADO` o `AGOTADO`, por lo que el backfill no crea ni limpia su aviso legacy. La transacción de confirmación cancela el evento, limpia la intención y crea el aviso a Compras con el ID estable existente en el mismo commit; si el backfill ve luego un flag legacy, la inserción duplicada se ignora. No es necesario modificar el backfill.

## Pruebas y documentación

Usar MongoDB de Testcontainers, nunca MongoDB local. Cubrir flujo feliz, corte antes de Reservas, respuesta perdida, `503` con intención pendiente, `ERROR_FINAL` por agotamiento, token ausente, avisos antiguos sin método, aviso a MS7 con y sin fila y ausencia de duplicados. Actualizar README y colección Postman, incluyendo el contrato confirmado con Reservas, el significado de `503` y la intervención en `ERROR_FINAL`.
