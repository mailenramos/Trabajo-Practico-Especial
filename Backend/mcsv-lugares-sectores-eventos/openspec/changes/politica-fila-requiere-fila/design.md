# Diseño: política de fila

## Modelo y compatibilidad

MS1 es la autoridad de `requiereFila` en el documento `Evento`. Un evento nuevo inicia en `false`; para documentos sin el campo, un booleano primitivo se interpreta como `false` al leer, sin escritura de normalización ni migración. El `false` de un documento antiguo también equivale a `false` al comparar para una actualización idempotente.

## Consulta interna

`GET /internal/eventos/{eventoId}` lee el evento persistido actual en cada solicitud, sin caché, para cualquier estado; responde `404` si no existe. Devuelve:

| Nivel | Campos |
|---|---|
| Evento | `eventoId` (ID existente), `nombre`, `tipo`, `fechaHora` (ISO-8601 UTC), `ciudad`, `estado`, `organizadorId`, `requiereFila`, `sectores` |
| Cada sector | `sectorId`, `nombre`, `tipo`, `filas`, `asientosPorFila`, `capacidad`, `precio` |

Se conservan los campos y formatos del contrato de Compras, incluido `fechaInicio` en ISO-8601 UTC como alias existente de `fechaHora`, el estado en minúsculas y `precio: null` si aún no se definió. Los campos de dimensiones no aplicables mantienen sus valores almacenados, incluidos `null`; no se derivan capacidades ni se inventan otros datos de reporte. `ciudad` es la copia que ya tiene el evento.

## Actualización por MS7

`PUT /internal/eventos/{eventoId}/politica-fila` recibe un objeto JSON con `requiereFila` booleano obligatorio y responde `200` con `{"eventoId":"...","requiereFila":true}` o `false`. Rechaza body ausente, JSON mal formado, campo omitido, `null` o valor no booleano con `400` y el formato de error estándar; no convertir cadenas ni números a booleanos. Un ID inexistente responde `404`.

Evaluar primero si el evento existe (`404`), luego validar el body (`400`), incluso si el evento está cancelado, y después rechazar con `409` solo si se solicita activar un evento cancelado, aunque ya estuviera en `true`. Desactivar un evento cancelado está permitido. Comparar el valor lógico antes de guardar: si ya coincide, responder `200` sin persistir ni incrementar la `@Version` existente; esto incluye un `false` legado sin campo. Si cambia, persistir normalmente con control optimista de versión, para no sobrescribir una cancelación o actualización concurrente; ante conflicto, usar la respuesta `409` existente y permitir reintentar. No se define otra restricción de estado.

La activación o desactivación la informa MS7; la pausa es interna a MS7 y no actualiza este indicador. `requiereFila=false` no cambia el flujo habitual de reservas.

## Seguridad y alcance

Ambas rutas se invocan directamente entre servicios, sin requerir `X-User-Id`, `X-User-Roles` ni el encabezado legado `X-User-Role`; no se publican por el Gateway. No se agrega ni se presume validación entrante de `X-Service-Token` en este cambio. No se modifica la autenticación saliente de outbox.

## Verificación

Probar con Mongo real vía Testcontainers lectura de documentos sin campo, ausencia de escrituras y versión intacta al repetir, cambios de versión cuando corresponda y conflicto ante actualización concurrente. Cubrir por HTTP consulta completa y actualizada, activación y desactivación, cancelado con ambos valores, inexistente, body inválido y ausencia de headers de usuario. Conservar pruebas del contrato de Compras; verificar que la colección Postman use las nuevas respuestas y no dependa de autenticación de usuario.
