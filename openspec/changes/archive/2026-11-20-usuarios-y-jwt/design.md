## Contexto

Solo el Gateway valida el JWT; los microservicios están en la red interna y siguen leyendo la identidad por encabezados, como en la Entrega 1 (supuesto 3 de F12). MS6 es el único emisor de tokens.

## Decisiones

### Contrato del token

- HS256 con `JWT_SECRET` (≥ 32 bytes UTF-8), compartido por MS6 y el Gateway por variable de entorno.
- Claims: `sub` (UUID del usuario), `email`, `roles` (lista ordenada), `iss` (`JWT_ISSUER`, por defecto `ticketya-usuarios`), `iat`, `exp` (`JWT_EXPIRATION`, por defecto `PT1H`).
- Sin refresh token: los cambios de roles o de estado rigen desde el próximo login (como máximo una hora de desfase).

### Propagación de identidad

| Encabezado | Valor | Cuándo |
|---|---|---|
| `X-User-Id` | `sub` | Token válido |
| `X-User-Roles` | roles separados por coma, orden alfabético | Token válido |
| `X-User-Role` | el único rol | Token válido y usuario con un solo rol (compatibilidad transitoria) |

El Gateway elimina los tres encabezados entrantes en todas las rutas antes de reenviar.

### Persistencia de MS6

- Tablas `usuarios` y `usuario_roles` (migración `V1__crear_usuarios.sql`).
- El correo se guarda normalizado (`trim` + minúsculas) con `UNIQUE` y un `CHECK`. Dos registros simultáneos con el mismo correo: uno gana la restricción y el otro recibe `409 CORREO_YA_REGISTRADO`.
- `@Version` en `usuarios` para detectar modificaciones concurrentes (`409 MODIFICACION_CONCURRENTE`).

### Atomicidad de las reglas de administradores

Antes de quitar el rol `ADMIN` o desactivar a un administrador activo, MS6 bloquea los administradores activos con `SELECT ... FOR UPDATE` dentro de la transacción y rechaza la operación con `409 ULTIMO_ADMINISTRADOR` si no queda otro. Un administrador no puede desactivarse a sí mismo (`409 AUTODESACTIVACION`).

### Seguridad del login

- Correo inexistente y contraseña incorrecta devuelven el mismo `401 CREDENCIALES_INVALIDAS`. Si el correo no existe se compara igual contra un hash BCrypt ficticio, para no revelar por el tiempo de respuesta cuál dato falló.
- Una cuenta inactiva con credenciales correctas recibe `403 USUARIO_INACTIVO` y no se emite token.
- Los logs registran correo y resultado, nunca la contraseña. Los `toString()` de pedidos y propiedades ocultan las credenciales.

### Arranque

- Sin `ADMIN_EMAIL` o `ADMIN_PASSWORD` el contexto no inicia y muestra un mensaje claro, sin los valores.
- El administrador inicial se crea solo si no existe ningún `ADMIN` activo. Si el correo ya está registrado, se le agrega `ADMIN` sin cambiar su contraseña.
- Con `USUARIOS_DATOS_PRUEBA=true` se crean cuatro usuarios de prueba (uno por rol) con UUID fijos y contraseña `Prueba123`.

### Contratos con otros servicios

- `GET /internal/usuarios/{id}/contacto` → `{correo}` (Notificaciones). Responde también para cuentas inactivas.
- `GET /internal/usuarios/por-correo?correo=` → `{id, roles, activo}` (Lugares, sectores y eventos).

Contrato a acordar: si Lugares, sectores y eventos necesita rechazar personal de acceso inactivo, puede usar el campo `activo` (agregado a lo pedido en US-12.7).

## Riesgos

- MS1 y MS3 todavía leen `X-User-Role`: un usuario con varios roles no lo recibe hasta que esos servicios migren.
- `GET /eventos` es pública en el Gateway, pero MS1 aún exige encabezados de identidad.
