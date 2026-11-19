# Design

## Context

MS8 ya persiste un documento de preferencias por usuario, usa Spring MVC y `RestClient`, centraliza errores HTTP y comparte `SERVICIO_TOKEN` con la integración de Compras. MS1 notifica publicaciones mediante su outbox. Notificaciones acepta eventos por `POST /internal/notificaciones` y deduplica las entregas repetidas según `acontecimientoId`, `tipo` y `usuarioId`.

## Goals / Non-Goals

**Goals:**

- Aceptar únicamente callbacks de MS1 autenticados por token, sin depender de headers de usuario.
- Encontrar destinatarios que coincidan tanto por artista participante como por ciudad, con las reglas de comparación de recomendaciones.
- Entregar el contrato acordado con Notificaciones y permitir que la outbox reintente si hubo fallos parciales.
- No guardar recibos ni avisos enviados en MS8.

**Non-Goals:**

- Cambiar la outbox, el contrato de MS1, Notificaciones, Gateway o el endpoint de recomendaciones.
- Reintentar internamente, procesar en paralelo, introducir colas, persistencia de avisos o nuevas dependencias.
- Usar `tiposEvento` como criterio de notificación o deduplicar avisos fuera de Notificaciones.

## Decisions

### Callback y autenticación

- Agregar `POST /internal/preferencias/eventos-publicados` con un DTO validado que requiere `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` y `fechaHora`. Los campos textuales obligatorios no pueden ser nulos ni blancos; `participantes` debe ser una lista de textos. Una lista vacía de participantes es válida, pero no produce destinatarios.
- `fechaHora` acepta el formato emitido por MS1: `Evento.fechaHora` es `Instant` y `PublicacionAvisoOutboxFactory` envía `evento.getFechaHora().toString()`, ISO-8601 UTC con `Z` y fracción de segundos cuando corresponde, por ejemplo `2030-01-02T03:04:05Z`. El DTO debe parsear este formato sin imponer un patrón fijo de segundos o fracción. Si recibe un ISO local sin zona, lo interpreta en `America/Argentina/Buenos_Aires`; devuelve `400` solo ante un valor ausente o que no se pueda parsear.
- Proteger solo esta ruta con una validación de `X-Service-Token` contra `SERVICIO_TOKEN`. Si la configuración está ausente o en blanco, o el header falta o no coincide exactamente, responder `403`; nunca aceptar una llamada cuando el token no está configurado. No registrar el valor del token.
- No aplicar el interceptor de identidad de cliente a esta ruta ni agregarla al Gateway. Los errores de validación usan el `400` estándar de MS8; los errores de token usan `403`.
- Tratar `X-Aviso-Id` como dato de trazabilidad opcional; si no está presente o está en blanco, generar un UUID de correlación para el callback.

### Selección de destinatarios

- Leer los documentos de preferencias existentes y seleccionar usuarios solo cuando al menos un artista guardado coincida con algún participante y alguna ciudad guardada coincida con `ciudad`. Una coincidencia de solo uno de los criterios no alcanza. No evaluar `tiposEvento`.
- Comparar texto recortado y convertido a minúsculas con `Locale.ROOT`, igual que el cálculo de recomendaciones. Los valores ya almacenados por el PUT de preferencias están recortados; normalizar también participantes y ciudad entrantes.
- Deduplicar por `usuarioId` antes del envío para asegurar como máximo un aviso por usuario y evento, aunque coincidan varios artistas o ciudades.
- Mantener la búsqueda en memoria sobre los documentos existentes para reutilizar la semántica exacta de comparación y evitar índices normalizados o cambios de esquema en este alcance.

### Cliente y payload de Notificaciones

- Configurar `NOTIFICACIONES_BASE_URL` con default host `http://localhost:8090` y valor Compose `http://notificaciones:8090`. Usar un `RestClient` con timeout de conexión de 2 segundos y lectura de 5 segundos.
- Enviar un `POST {NOTIFICACIONES_BASE_URL}/internal/notificaciones` por destinatario, con `Content-Type: application/json` y el mismo `X-Service-Token` recibido/verificado contra `SERVICIO_TOKEN`.
- Construir `acontecimientoId` como `publicacion-{eventoId}-{usuarioId}`, mantenerlo dentro del máximo de 100 caracteres y no truncarlo ni cambiarlo, para conservar el contrato de deduplicación acordado.
- Enviar `tipo=NUEVO_EVENTO_DE_INTERES`, `versionEsquema=1`, `recursoId=eventoId`, `usuarioId` del destinatario y `ocurridoEn` como el instante UTC del callback. Formatear `datos.fecha` desde `fechaHora` del evento usando `dd/MM/yyyy HH:mm` y `America/Argentina/Buenos_Aires`; asignar `datos.evento=nombre` y `datos.lugar=ciudad`.
- Usar `X-Aviso-Id` como `correlacionId` cuando tenga contenido; de lo contrario, generar un UUID por callback y reutilizarlo para todos sus destinatarios.

### Fan-out, fallos y respuesta

- Procesar destinatarios secuencialmente y continuar con los siguientes aunque un envío falle.
- Contar como enviado solo el envío que devuelve `202 Accepted`; un duplicado aceptado con el mismo `202` cuenta igual que uno nuevo. Tratar otros códigos HTTP, incluidos `4xx`, `5xx`, y excepciones de timeout/transporte como fallos individuales.
- Si no hay fallos, responder `202` con `{ "destinatarios": total, "enviados": aceptados }`, incluidos los contadores en cero cuando no haya destinatarios.
- Si al menos un envío falla, completar los intentos restantes, registrar un WARN con los contadores de fallos sin incluir tokens y responder `503` con el formato de error estándar de MS8. La outbox reintentará el callback y Notificaciones deduplicará entregas ya aceptadas.
- No añadir almacenamiento local de idempotencia; el identificador de acontecimiento y la deduplicación de Notificaciones cubren los reintentos parciales.

### Configuración, documentación y pruebas

- Agregar `NOTIFICACIONES_BASE_URL` al Compose con default Docker `http://notificaciones:8090`; pasar `SERVICIO_TOKEN` desde el entorno sin valor predeterminado ni credenciales incorporadas.
- Documentar el contrato, token, configuración y uso del stub existente `postman/notificaciones-stub.js`, y las simplificaciones: sin persistencia de avisos, procesamiento secuencial, lugar representado por la ciudad y `503` si falla cualquier destinatario. Documentar también que la selección recorre las preferencias existentes.
- Agregar una carpeta en Postman para el endpoint interno, con la URL base, `SERVICIO_TOKEN` como variable y `X-Aviso-Id` opcional.
- Probar token y validación en MVC; selección y deduplicación de destinatarios; payload y formato temporal con reloj controlado; y fan-out con `MockRestServiceServer`, incluyendo fallo parcial y continuación de envíos.

## Risks / Trade-offs

- [Leer todos los documentos de preferencias puede aumentar el costo por evento a medida que crecen los usuarios] → Mantenerlo como implementación simple del alcance actual y documentar el recorrido; no agregar índices ni un modelo de búsqueda separado sin requerimiento.
- [Un fallo parcial provoca reenvío de notificaciones aceptadas durante el reintento de la outbox] → Usar el `acontecimientoId` estable prescrito y confiar en la deduplicación confirmada de Notificaciones.
- [El identificador compuesto puede superar 100 caracteres si los IDs son inusualmente largos] → No truncarlo, porque produciría claves inestables o colisiones; el destinatario fallido cuenta como fallo de entrega y el callback responde `503`.
- [Una indisponibilidad de Notificaciones demora el callback hasta los timeouts secuenciales] → Limitar cada llamada a 2 segundos de conexión y 5 segundos de lectura; el fan-out permanece secuencial según el alcance.

## Migration Plan

No hay migración de datos ni cambio de esquema. Desplegar MS8 con `SERVICIO_TOKEN` y `NOTIFICACIONES_BASE_URL` configurados, y habilitar el callback en la outbox de MS1. Verificar `202` con un evento sin destinatarios y con destinatarios coincidentes. Si falla algún envío, la respuesta `503` permite el reintento de la outbox sin duplicar los avisos ya aceptados.
