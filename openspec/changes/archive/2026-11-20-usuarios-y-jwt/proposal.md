## Why

En la Entrega 1 no había autenticación: el usuario se identificaba con encabezados que enviaba el propio cliente. La Entrega 2 (feature F12, US-12.1 a US-12.8) exige un servicio de usuarios que registre cuentas, verifique credenciales y emita JWT, y un Gateway que valide esos tokens y propague una identidad que el cliente no pueda falsificar. La carpeta `Backend/mcsv-usuarios-autenticacion` solo tenía directorios vacíos y el Gateway leía un único claim `role`.

## What Changes

- Implementar MS6 (`Backend/mcsv-usuarios-autenticacion`, puerto `8087`, PostgreSQL propio en `5436`):
  - Registro público como `CLIENTE`, con contraseña en BCrypt y unicidad del correo normalizado.
  - Login que emite un JWT HS256 con `sub`, `email`, `roles`, `iss`, `iat` y `exp` (una hora por defecto).
  - Perfil propio (`/usuarios/me`), administración de usuarios, roles y estado (borrado lógico) para `ADMIN`.
  - Rutas internas `/internal/usuarios/{id}/contacto` y `/internal/usuarios/por-correo`.
  - Administrador inicial desde `ADMIN_EMAIL` / `ADMIN_PASSWORD` (obligatorias) y un usuario de prueba por rol.
- Adaptar el API Gateway:
  - Validar `iss`, `exp` y la lista `roles`; un usuario puede tener varios roles.
  - Propagar `X-User-Id` y `X-User-Roles`; eliminar siempre los encabezados de identidad entrantes, también en rutas públicas.
  - Mantener `X-User-Role` solo para usuarios con un único rol, como compatibilidad transitoria con MS1 y MS3.
  - Publicar las rutas de MS6 y declarar como públicas `POST /auth/registro`, `POST /auth/login`, `GET /eventos` y `GET /eventos/{id}`.
  - Distinguir `401 NO_AUTENTICADO` (sin token) de `401 TOKEN_INVALIDO` (vencido, firma o emisor incorrectos, mal formado).
  - Permitir `PATCH` y `DELETE` en CORS.

## Capabilities

### New Capabilities

- `usuarios-autenticacion` (MS6): ver `Backend/mcsv-usuarios-autenticacion/openspec/specs/usuarios-autenticacion/spec.md`.

### Modified Capabilities

- `api-gateway`: autenticación con roles múltiples, emisor, rutas públicas explícitas y rutas de MS6.

## Servicios de los que depende

- MS1, MS2, MS3 y MS5 reciben la identidad por encabezados; cada uno debe migrar de `X-User-Role` a `X-User-Roles` y conservar sus controles de titularidad (US-12.2).
- Notificaciones y Lugares, sectores y eventos consumen las rutas internas de MS6 (US-12.7).

## Fuera de alcance

- Adaptar MS1, MS2, MS3 y MS5 a `X-User-Roles` y la consulta pública de eventos sin identidad en MS1 (cada integrante adapta su servicio).
- Login y envío del token desde el frontend.
- Refresh token y cierre de sesión en el servidor (decisión del grupo).
- Autenticación entre servicios para las rutas `/internal/**` (solo accesibles desde la red interna).
