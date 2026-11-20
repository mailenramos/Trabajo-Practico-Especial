## Contexto

- `UsuarioActual(id, rol)` se arma en `UsuarioActualResolver` y se inyecta en los controladores.
- `Permisos.esAdministrador` y `exigirAdministrador` comparan `rol` con `compras.rol-administrador`.
- Falta de `X-User-Id` devuelve 401 `USUARIO_NO_IDENTIFICADO`. Falta de permiso de administrador devuelve 403 `SOLO_ADMINISTRADOR`. Ambos se mantienen.

## Decisiones

- **Modelo:** `UsuarioActual(String id, Set<String> roles)`. Los roles se normalizan a mayúsculas, sin espacios y sin vacíos. Un conjunto vacío es válido (usuario sin roles).
- **Lectura del encabezado:** `X-User-Roles` separado por coma. Si no está, se usa `X-User-Role` como un único rol (compatibilidad). Si están los dos, manda `X-User-Roles`.
- **Permisos:** `esAdministrador` es `roles.contains(rolAdministrador en mayúsculas)`. No se elige "un rol" entre varios.
- **Alcance del cambio de código:** se reemplaza todo uso de `usuario.rol()` por la consulta de pertenencia. Antes de editar se buscan todos los usos (`grep rol()`), incluidos tests, para no dejar ninguno.
- **Sin cambios en titularidad:** las reglas "solo el dueño de la compra" siguen usando `usuario.id()`.
- **Registros:** no se registran los encabezados completos de identidad.
