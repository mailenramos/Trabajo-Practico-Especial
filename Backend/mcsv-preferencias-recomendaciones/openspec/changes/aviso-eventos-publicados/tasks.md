# Tasks

## 1. Recibir y proteger el callback

- [x] 1.1 Agregar DTO de publicación con validación de campos requeridos, lista `participantes` y `fechaHora` en el formato UTC de `Instant.toString()` que envía MS1 (interpretar timestamps sin zona en Buenos Aires); cubrir cuerpos inválidos y un ejemplo real `2030-01-02T03:04:05Z` con MVC.
- [x] 1.2 Proteger solo la ruta interna con `X-Service-Token` y `SERVICIO_TOKEN`; devolver 403 por header ausente/incorrecto o configuración vacía, sin requerir identidad. Cubrir los tres casos con MVC.

Commit sugerido: `feat(ms8): secure event publication callback`

## 2. Seleccionar destinatarios

- [x] 2.1 Implementar la selección desde las preferencias existentes solo cuando coincidan artista y ciudad, comparando con trim y `Locale.ROOT`, ignorando `tiposEvento` y deduplicando por usuario.
- [x] 2.2 Probar solo artista y solo ciudad (no destinatario), ambos (sí, una sola vez), mayúsculas y espacios, lista de participantes vacía (no se avisa) y ausencia de coincidencias.

Commit sugerido: `feat(ms8): select clients interested in published events`

## 3. Integrar el cliente de Notificaciones

- [x] 3.1 Configurar `NOTIFICACIONES_BASE_URL` (host y Compose), `SERVICIO_TOKEN` sin default útil y `RestClient` con timeouts de conexión 2 s y lectura 5 s.
- [x] 3.2 Crear el payload acordado, con `acontecimientoId` estable de hasta 100 caracteres, correlación recibida o UUID, `ocurridoEn` UTC y `datos.fecha` en la zona de Buenos Aires; verificar ruta, headers y payload exacto con `MockRestServiceServer`.

Commit sugerido: `feat(ms8): build notification delivery client`

## 4. Completar el fan-out y manejar fallos

- [x] 4.1 Enviar secuencialmente a todos los destinatarios; contar solo respuestas 202 y responder 202 con ambos contadores cuando no haya fallos o no haya destinatarios.
- [x] 4.2 Continuar tras cada fallo, registrar la cantidad fallida sin el token y devolver 503 estándar si falla al menos una entrega; probar que los destinatarios restantes igualmente se intentan.

Commit sugerido: `feat(ms8): deliver event alerts with partial-failure handling`

## 5. Documentar y facilitar pruebas

- [ ] 5.1 Agregar `NOTIFICACIONES_BASE_URL` al Compose con default `http://notificaciones:8090` y pasar `SERVICIO_TOKEN` desde el entorno sin credenciales incorporadas; validar Compose base.
- [ ] 5.2 Documentar en README cómo levantar el stub existente `postman/notificaciones-stub.js` con `SERVICIO_TOKEN`, usar los modos `ok`, `error` y `timeout`, consultar `GET /_stub/notificaciones` y configurar una falla parcial con `PUT /_stub/fallar/{usuarioId}`. No crear ni modificar el stub.
- [ ] 5.3 Documentar endpoint, autenticación, configuración, uso del stub y simplificaciones en README: sin persistencia, secuencial, lugar igual a ciudad, recorrido de preferencias y 503 ante cualquier fallo.
- [ ] 5.4 Agregar carpeta y request interna a Postman con base URL, variable de token y `X-Aviso-Id` opcional; validar el JSON y la ruta.

Commit sugerido: `docs(ms8): document published-event notification flow`

## 6. Validar el cambio

- [ ] 6.1 Ejecutar `mvnw.cmd test` con Docker disponible y corregir cualquier fallo de pruebas MVC, servicio, clientes o persistencia.
- [ ] 6.2 Ejecutar `openspec validate aviso-eventos-publicados --strict` desde MS8 y corregir cualquier error o advertencia.
- [ ] 6.3 Ejecutar `docker compose config --quiet` y `git diff --check`.

Commit sugerido: `test(ms8): verify published-event notification change`
