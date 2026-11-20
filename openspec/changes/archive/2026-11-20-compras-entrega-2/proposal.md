## Por qué

La Entrega 2 le pide a Gestión de compras y devoluciones (MS3) tres cosas nuevas:
- Darle a Recomendaciones (MS8) dos consultas internas sobre las compras confirmadas: tipos de evento comprados por un cliente y co-compras de un evento.
- Recibir de Eventos (MS1) el aviso de que un evento cambió de fecha, horario o lugar, actualizar las fechas guardadas y avisar a los compradores afectados.
- Avisar a Notificaciones (MS9) cuando ocurre algo relevante en una compra o devolución, sin bloquear ni revertir la operación principal.
- Controlar el acceso a esos endpoints internos con el token de servicio acordado para la Entrega 2.

## Qué cambia

- `GET /internal/eventos/{eventoId}/co-compras`: eventos que también compraron quienes compraron el evento indicado, con cantidad de compradores en común (máximo 10 por defecto, tope 20).
- `GET /internal/usuarios/{usuarioId}/historial-preferencias`: tipos de evento de las compras confirmadas del cliente, sin repetir, con cantidad, y `eventosComprados`: los `eventoId` distintos de esas compras, del más reciente al más antiguo, máximo 20 (lo pide Recomendaciones para co-compras).
- La compra guarda el tipo de evento (`tipoEvento`), copiado de Eventos al iniciar la compra. Las compras anteriores quedan con `tipoEvento` nulo y no cuentan en el historial.
- `POST /internal/eventos-modificados`: recibe de Eventos la modificación (`eventoId`, `version`, `fechaHora`, `lugar`, `ciudad`), la registra de forma idempotente por `eventoId` + `version`, actualiza `eventoFechaInicio` en compras y solicitudes de devolución y genera un aviso EVENTO_MODIFICADO por cada comprador.
- Outbox de avisos: al cambiar el estado de una compra, devolución o reintegro se guarda un aviso pendiente en la misma transacción. Una tarea programada los envía a `POST /internal/notificaciones` con `X-Service-Token`, con reintentos limitados.
- Los endpoints `/internal/` nuevos exigen `X-Service-Token`; sin él o con uno incorrecto responden 403.

## Fuera de alcance

- Que Eventos permita modificar eventos y emita el aviso (lo hace su responsable).
- Adaptación a JWT y a varios roles (`X-User-Roles`): es un cambio aparte, obligatorio para la Entrega 2.
- Recomendaciones en sí (lógica de MS8) y los endpoints de Notificaciones.
- Reemplazar el encabezado `X-User-Role` por JWT en los endpoints públicos.
- Reconstruir el tipo de evento de compras anteriores (no hay migración de datos).
- Docker, compose y pruebas de carga del grupo.

## Servicios externos de los que depende

- Gestión de lugares, sectores y eventos (MS1): llama a `POST /internal/eventos-modificados` y `GET /eventos/{eventoId}` debe devolver `tipo` (a confirmar con su responsable; el servicio ya lo guarda).
- Gestión de notificaciones (MS9): `POST /internal/notificaciones` (ya implementado).
- Gestión de preferencias y recomendaciones (MS8): consume los dos endpoints nuevos.

## Impacto

Entidad `Compra` (columna nueva), cliente de Eventos, repositorio de compras, un controlador interno nuevo, una tabla de avisos pendientes, una tarea programada y un filtro de autorización de servicio.
