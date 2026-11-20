# MS6 - Gestión de usuarios y autenticación

Registra usuarios, verifica credenciales y emite tokens JWT (feature F12, US-12.3 a US-12.8). El API Gateway valida esos tokens y propaga la identidad a los demás microservicios con `X-User-Id` y `X-User-Roles`.

- Puerto: `8087`.
- Base de datos: PostgreSQL 16 en `localhost:5436` (base, usuario y contraseña `usuarios`, solo para desarrollo).
- Esquema con Flyway (`src/main/resources/db/migration`); Hibernate solo valida (`ddl-auto: validate`).
- Contraseñas con BCrypt (salt aleatorio por contraseña). Nunca aparecen en respuestas ni en logs.
- Swagger UI: `http://localhost:8087/swagger-ui.html`.

## Ejecución local (PowerShell)

1. Base de datos:

   ```powershell
   cd .\Backend\mcsv-usuarios-autenticacion
   docker compose up -d postgres
   ```

2. Servicio. `JWT_SECRET` debe ser **el mismo valor** que usa el Gateway (mínimo 32 bytes). `ADMIN_EMAIL` y `ADMIN_PASSWORD` son obligatorias: sin ellas el servicio no arranca.

   ```powershell
   $env:JWT_SECRET = '<clave compartida con el Gateway, al menos 32 caracteres>'
   $env:ADMIN_EMAIL = 'admin@ticketya.local'
   $env:ADMIN_PASSWORD = '<contraseña con 8+ caracteres, letras y números>'
   .\mvnw.cmd spring-boot:run
   ```

3. Tests (no necesitan Docker; usan H2 en modo PostgreSQL):

   ```powershell
   .\mvnw.cmd test
   ```

## Configuración

| Variable | Predeterminado | Descripción |
|---|---|---|
| `SERVER_PORT` | `8087` | Puerto HTTP. |
| `DB_URL` | `jdbc:postgresql://localhost:5436/usuarios` | URL JDBC. |
| `DB_USER` / `DB_PASSWORD` | `usuarios` / `usuarios` | Credenciales de la base. |
| `JWT_SECRET` | Obligatoria | Secreto HS256 compartido con el Gateway (≥ 32 bytes UTF-8). |
| `JWT_ISSUER` | `ticketya-usuarios` | Claim `iss`; el Gateway exige el mismo valor. |
| `JWT_EXPIRATION` | `PT1H` | Vigencia del token (duración ISO-8601). |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | Obligatorias | Administrador inicial. Se crea solo si no hay ningún ADMIN activo; no se duplica en reinicios. Si el correo ya existe, se le agrega el rol ADMIN sin cambiar su contraseña. |
| `ADMIN_NOMBRE` / `ADMIN_APELLIDO` | `Administrador` / `TicketYa` | Datos del administrador inicial. |
| `USUARIOS_DATOS_PRUEBA` | `true` | Crea un usuario de prueba por rol (ver abajo). Poner `false` fuera de desarrollo. |

## Usuarios de prueba

Con `USUARIOS_DATOS_PRUEBA=true` se crean (si no existen) estos usuarios, todos con la contraseña `Prueba123`. Los identificadores son fijos para poder usarlos desde Postman o en datos de otros servicios:

| Correo | Rol | Id |
|---|---|---|
| `cliente@ticketya.test` | CLIENTE | `00000000-0000-4000-8000-000000000001` |
| `organizador@ticketya.test` | ORGANIZADOR | `00000000-0000-4000-8000-000000000002` |
| `admin@ticketya.test` | ADMIN | `00000000-0000-4000-8000-000000000003` |
| `personal@ticketya.test` | PERSONAL_ACCESO | `00000000-0000-4000-8000-000000000004` |

## API

Rutas públicas a través del Gateway (sin token):

| Método y ruta | Descripción |
|---|---|
| `POST /auth/registro` | `{nombre, apellido, correo, password}`. Crea un cliente activo; cualquier rol enviado se ignora. `201` con el perfil; `409 CORREO_YA_REGISTRADO`; `400 VALIDACION` con `campos`. |
| `POST /auth/login` | `{correo, password}`. `200 {token, tipoToken: "Bearer", expiraEn: 3600}`; `401 CREDENCIALES_INVALIDAS` (mismo mensaje si falla el correo o la contraseña); `403 USUARIO_INACTIVO`. |

Rutas protegidas (el Gateway exige JWT y completa `X-User-Id` / `X-User-Roles`):

| Método y ruta | Rol | Descripción |
|---|---|---|
| `GET /usuarios/me` | cualquiera | Perfil propio (id, nombre, apellido, correo, roles, activo). |
| `PUT /usuarios/me` | cualquiera | `{nombre, apellido}`. Roles, estado y correo enviados se ignoran. |
| `GET /usuarios?rol=&correo=&page=&size=` | ADMIN | Lista paginada (máx. 100 por página), ordenada por correo; `correo` es coincidencia parcial. |
| `GET /usuarios/{id}` | ADMIN | Datos, roles y estado de un usuario. |
| `PUT /usuarios/{id}/roles` | ADMIN | `{roles: [...]}` reemplaza los roles. `409 ULTIMO_ADMINISTRADOR` si deja el sistema sin administradores activos. Rige desde el próximo login. |
| `PUT /usuarios/{id}/estado` | ADMIN | `{activo: true|false}`. Borrado lógico. `409 AUTODESACTIVACION` si un admin intenta desactivarse. |

Rutas internas (el Gateway **no** las enruta; responde 404 desde afuera):

| Método y ruta | Consumidor | Respuesta |
|---|---|---|
| `GET /internal/usuarios/{id}/contacto` | Notificaciones | `{correo}`; también para cuentas inactivas. |
| `GET /internal/usuarios/por-correo?correo=` | Lugares, sectores y eventos | `{id, roles, activo}`. |

Los errores usan el formato común: `timestamp`, `status`, `error`, `mensaje` y, cuando corresponde, `campos`.

### Token JWT

HS256 firmado con `JWT_SECRET`. Claims: `sub` (id del usuario), `email`, `roles` (lista), `iss`, `iat` y `exp`. No incluye contraseña ni otros datos sensibles. No hay refresh token ni logout en el servidor: al vencer, se inicia sesión de nuevo. Un usuario desactivado o con roles cambiados conserva su token anterior hasta que vence (como máximo una hora).

### Logs

- `login correo=... resultado=EXITOSO|CREDENCIALES_INVALIDAS|USUARIO_INACTIVO` (nunca la contraseña).
- `cambio_roles correlationId=... adminId=... usuarioId=... rolesAnteriores=... rolesNuevos=...`, con el `X-Correlation-Id` que propaga el Gateway. Si falta o no es un UUID, se genera uno; siempre se devuelve en la respuesta.

Especificación (requisitos y escenarios): [openspec/specs/usuarios-autenticacion/spec.md](openspec/specs/usuarios-autenticacion/spec.md). Registro del cambio: [openspec/changes/archive/2026-11-20-usuarios-y-jwt](../../openspec/changes/archive/2026-11-20-usuarios-y-jwt).

## Decisiones

- Requisitos de contraseña: 8 a 72 caracteres (72 es el límite de BCrypt), con al menos una letra y un número.
- El correo se guarda normalizado (sin espacios al borde y en minúsculas) con una restricción `UNIQUE`; dos registros simultáneos con el mismo correo crean un solo usuario.
- El servicio confía en `X-User-Id` / `X-User-Roles` porque solo es accesible desde la red interna a través del Gateway, que elimina esos encabezados si los envía el cliente.
- Cambiar roles o desactivar al último administrador activo se rechaza; los admins activos se bloquean (`SELECT ... FOR UPDATE`) para evitar carreras.
