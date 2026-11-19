# Diseño: personal de acceso

## Modelo y asignaciones

Una colección propia de asignaciones almacena `eventoId`, `usuarioId`, `estado` (`ACTIVA` o `REVOCADA`), `asignadaEn` y `revocadaEn` (instantes UTC; `revocadaEn` es `null` mientras está activa). Un índice único compuesto `(eventoId, usuarioId)` cubre ambos estados: existe como máximo un documento por par, no uno por transición. Al crear o reactivar, `asignadaEn` registra la activación actual y `revocadaEn` queda `null`; al revocar se conserva el documento y se registra `revocadaEn`. Repetir una revocación no modifica las fechas. Esta simplificación conserva el estado y las fechas de la última activación/revocación, no un historial completo.

Las operaciones de alta y reactivación deben ser seguras ante concurrencia: usar el índice único y una transición atómica condicionada por el estado. Un alta simultánea para el mismo par no puede crear duplicados; la que encuentre la asignación activa responde `409`. La revocación cambia únicamente una asignación activa y deja intacta una ya revocada. Una persona puede estar asignada a distintos eventos.

## Acceso de organizador y administrador

`POST` y `GET /eventos/{eventoId}/personal-acceso`, y `DELETE /eventos/{eventoId}/personal-acceso/{usuarioId}`, usan `X-User-Id` y la lista completa de `X-User-Roles`: autorizan al `ORGANIZADOR` cuyo ID coincide con el propietario o a `ADMIN`. No aceptar otros roles ni un organizador ajeno; si no se cumplen las condiciones de identidad y autorización, responder `403`. Evento inexistente: `404`. No dependen del estado del evento.

El alta valida `correo`, consulta a MS6 antes de cualquier escritura y exige `PERSONAL_ACCESO` en los roles devueltos; no usar correo ni rol suministrado por el cliente como prueba de identidad. Ante un correo inexistente responde `404`, rol insuficiente `400` y fallo transitorio de MS6 `503`, sin crear ni reactivar nada. Con respuesta válida, crear o reactivar responde `201` con la asignación. `GET` lista `usuarioId`, `estado`, `asignadaEn` y `revocadaEn`, incluso registros revocados; una lista sin asignaciones está vacía. `DELETE` responde `204` también si ya estaba revocada y `404` si falta la asignación.

## Consulta de MS5

`GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` consulta la asignación actual sin caché. Devuelve `200` con `eventoId`, `usuarioId` y `autorizado=true` solo si está activa; sin asignación o revocada devuelve `false`, también si el evento no existe. Cada validación de QR requiere una consulta nueva para que la revocación sea visible inmediatamente.

La ruta es interna, no exige identidad de usuario y no se publica por el Gateway. No se agrega validación de `X-Service-Token` a ninguna ruta entrante de MS1; las rutas internas existentes conservan su comportamiento. Se mantiene la política actual de arranque Docker y pausa de outbox si falta el token.

## Cliente MS6 y configuración

Usar `RestClient`, conexión de 2 s y lectura de 5 s; `GET {USUARIOS_BASE_URL}/internal/usuarios/por-correo?correo=...` envía `X-Service-Token` con el valor configurado. Construir el query param con codificación URI, sin concatenar valores inseguros ni escribir tokens en logs o errores. Respuesta `404` de MS6 significa correo inexistente; timeout, error de transporte o `5xx` producen `503`. No guardar ninguna asignación hasta validar `id` y `roles`.

Agregar `usuarios.base-url` y `USUARIOS_BASE_URL` en configuración y Compose. **TODO:** confirmar con el grupo la URL host y la URL/nombre de servicio Docker de MS6 antes de fijar defaults; no usar el puerto no confirmado que figura en la configuración histórica de OpenSpec. También queda por confirmar el contrato exacto de MS6 (estructura y tipos de `id` y `roles`, respuesta de errores y comportamiento ante otros `4xx`); no interpretar respuestas inesperadas como éxito.

## Verificación y alcance

Probar con Mongo/Testcontainers el índice único, las transiciones atómicas concurrentes y la idempotencia; probar por HTTP los roles, titularidad, estados de respuesta, lookup de MS6 y timeouts. Comprobar la consulta interna sin headers de usuario y la lectura actual antes y después de revocar. Verificar el envío de `X-Service-Token` a MS6 y la ausencia de filtración del token en logs. Actualizar OpenAPI, README y Postman una vez implementado.
