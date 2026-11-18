# Design

## Context

El API Gateway valida el JWT y propaga `sub` en `X-User-Id` y los roles en `X-User-Roles`, separados por comas. MS1 debe autorizar con los mismos datos para todos los roles de una identidad.

## Decisions

### Interpretación de identidad y roles

- `X-User-Id` identifica al usuario autenticado.
- `X-User-Roles` se divide por comas; se recortan espacios externos de cada rol y se evalúa el resultado como un conjunto. No se toma el primer rol ni se asigna prioridad entre roles.
- Las operaciones autenticadas devuelven `401 Unauthorized` cuando falta el identificador o no hay roles; devuelven `403 Forbidden` si hay identidad pero ninguno de sus roles satisface la operación.
- Se usan los roles canónicos `CLIENTE`, `ORGANIZADOR` y `ADMIN`. `ADMIN` es el único rol con permisos administrativos; no se mantiene el alias legado `administrador`.

### Titularidad y permisos

- Crear, configurar y publicar eventos requiere `ORGANIZADOR`; las operaciones de administración de un evento requieren además que `X-User-Id` coincida con su `organizadorId`. Un usuario con varios roles no evita esta comprobación.
- `ADMIN` puede administrar lugares y sectores y realizar las operaciones administrativas ya existentes de eventos, incluida la cancelación.
- La consulta autenticada de lugares/sectores conserva el acceso para `ORGANIZADOR` y `ADMIN`; sus operaciones de escritura son solo para `ADMIN`.

### Visibilidad pública de eventos

- `GET /eventos` no requiere token y devuelve solamente eventos `publicado` o `agotado`, con los filtros y la paginación existentes.
- `GET /eventos/{eventoId}` no requiere token para eventos `publicado` o `agotado`. Los estados `cancelado` y `finalizado` no se exponen por esta ruta.
- Para un borrador, la consulta sin identidad completa responde `401`. Con identidad, solo se permite al organizador titular o a `ADMIN`; para los demás se responde `404` para no revelar el recurso. Los borradores no aparecen nunca en la búsqueda.
- El detalle de un borrador no consulta disponibilidad a Reservas. La disponibilidad de eventos públicos conserva el comportamiento existente.

### Rutas internas

Las rutas `/internal/...` y los callbacks internos continúan sin requerir `X-User-Id` ni `X-User-Roles` y no se publican en el Gateway.

## Testing

Las pruebas de API deben cubrir rol insuficiente (`403`), intento de administrar un evento ajeno (`403`), concesión basada en un rol presente entre varios y consulta de borrador sin token (`401`). También deben cubrir búsqueda/detalle publicados sin token, la privacidad de borradores y el acceso interno sin identidad de usuario.
