## 1. Autorización de servicio
- [x] 1.1 Filtro `X-Service-Token` para `/internal/**` nuevas (tiempo constante, 403 con `SERVICIO_NO_AUTORIZADO`, rechazo si no hay secreto) + tests

## 2. Co-compras
- [x] 2.1 Consulta en `CompraRepository` de co-compras (clientes distintos, solo CONFIRMADA, orden y límite) + test de repositorio
- [x] 2.2 `GET /internal/eventos/{eventoId}/co-compras` (límite 10 por defecto, tope 20, lista vacía con 200) + tests web
- [x] 2.3 Documentar el endpoint en `openapi.yaml`

## 3. Historial de preferencias
- [x] 3.1 Agregar `tipoEvento` a `Compra` y leerlo de Eventos al iniciar la compra (cliente REST y doble de prueba) + tests
- [x] 3.2 Consulta de historial agrupada por tipo + test de repositorio (ignora nulos y no confirmadas)
- [x] 3.3 `GET /internal/usuarios/{usuarioId}/historial-preferencias` (200 con lista vacía) + tests web
- [x] 3.4 Agregar `eventosComprados` (distintos, más recientes primero, máximo 20, incluye compras sin tipo) a la respuesta del historial + test de repositorio y test web
- [x] 3.5 Documentar el endpoint en `openapi.yaml`

## 4. Outbox de avisos
- [x] 4.1 Entidad y repositorio de `avisos_pendientes` con restricción única compuesta (`acontecimiento_id`, `tipo`, `usuario_id`) + test (dos compradores del mismo acontecimiento conviven; el mismo comprador repetido choca)
- [x] 4.2 Generar el aviso en la misma transacción para compra confirmada y entradas disponibles + tests (incluye rollback)
- [x] 4.3 Generar avisos de devolución aprobada, rechazada y resultado de reintegro + tests
- [x] 4.4 Generar un aviso por comprador afectado al cancelar un evento + test
- [x] 4.5 Cliente REST de Notificaciones (timeouts, `X-Service-Token`) detrás de una interfaz, con doble de prueba
- [x] 4.6 Tarea programada de envío: toma condicional, ENVIADO, reintentos con espera creciente y ERROR_FINAL + tests
- [x] 4.7 Recuperación de avisos PROCESANDO vencidos + test
- [x] 4.8 Prueba de integración: Notificaciones caído no afecta a la compra, la devolución ni el reintegro

## 5. Modificación de eventos
- [x] 5.1 Entidad y repositorio `modificaciones_evento` con restricción única (`evento_id`, `version`) + test de repositorio
- [x] 5.2 Actualización masiva de `eventoFechaInicio` en compras y solicitudes de devolución del evento + test
- [x] 5.3 Consulta de clientes distintos con compra confirmada del evento + test (varias compras del mismo cliente, no confirmadas)
- [x] 5.4 Servicio de modificación: transacción única (registro, fechas y avisos EVENTO_MODIFICADO), idempotencia y versión anterior + tests (incluye rollback)
- [x] 5.5 `POST /internal/eventos-modificados` con validaciones y códigos 202, 200, 400 y 403 + tests web
- [x] 5.6 Documentar el endpoint en `openapi.yaml`

## 6. Cierre
- [x] 6.1 Variables de entorno (token, URL de Notificaciones, reintentos) en README
- [x] 6.2 Actualizar historias de usuario y diagramas de MS3 en `docs/`
