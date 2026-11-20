# Tasks

## 1. MS6 - Base del servicio

- [x] 1.1 Crear el proyecto Spring Boot (Java 17, Spring Boot 3.5.0, wrapper Maven) con PostgreSQL, Flyway y H2 para tests.
- [x] 1.2 Migración `V1__crear_usuarios.sql` con unicidad del correo normalizado y roles válidos.
- [x] 1.3 Formato de error común y propagación de `X-Correlation-Id` a los logs.

## 2. MS6 - Autenticación (US-12.3, US-12.4)

- [x] 2.1 Registro como `CLIENTE` con BCrypt; ignorar el rol enviado. Tests: alta, rol ignorado, duplicado con mayúsculas/espacios, validación, registros simultáneos.
- [x] 2.2 Login con JWT HS256. Tests: claims, varios roles, mismo error para correo o contraseña, usuario inactivo, validación.

## 3. MS6 - Perfil y administración (US-12.5, US-12.6, US-12.8)

- [x] 3.1 `GET`/`PUT /usuarios/me`. Tests: sin identidad, datos sin hash, solo cambian nombre y apellido.
- [x] 3.2 Listado paginado con filtros y consulta por id (ADMIN). Tests: acceso denegado, filtros, no encontrado.
- [x] 3.3 Asignación de roles con protección del último administrador. Tests: varios roles, rol inexistente, autoasignación denegada, último admin.
- [x] 3.4 Activar/desactivar (borrado lógico). Tests: login rechazado, reactivación, autodesactivación.
- [x] 3.5 Administrador inicial y usuarios de prueba. Tests: no se duplica, falta de variables, credenciales ocultas.

## 4. MS6 - Consultas internas (US-12.7)

- [x] 4.1 Contacto por id y identidad por correo. Tests: solo correo, inactivo, no encontrado.

## 5. API Gateway (US-12.1)

- [x] 5.1 Validar `iss` y lista `roles`; propagar `X-User-Roles` y `X-User-Role` solo con un rol.
- [x] 5.2 Eliminar encabezados de identidad entrantes en todas las rutas.
- [x] 5.3 Rutas de MS6, rutas públicas explícitas y reglas por rol de `/usuarios/**`.
- [x] 5.4 `TOKEN_INVALIDO` vs `NO_AUTENTICADO`; CORS con `PATCH` y `DELETE`.
- [x] 5.5 Tests: rutas públicas, suplantación, varios roles, tokens vencidos/de otro emisor/con otra firma/mal formados, rutas internas de MS6 con 404.

## 6. Documentación

- [x] 6.1 README de MS6, README del Gateway y README principal; colección Postman de MS6.
- [x] 6.2 Spec `api-gateway` actualizada y spec `usuarios-autenticacion` de MS6.

## Pendiente (otros integrantes)

- [ ] Migrar MS1, MS2, MS3 y MS5 a `X-User-Roles` y aplicar los controles de titularidad de US-12.2.
- [ ] Permitir en MS1 la consulta pública de eventos sin identidad.
- [ ] Login y envío del token desde el frontend.
