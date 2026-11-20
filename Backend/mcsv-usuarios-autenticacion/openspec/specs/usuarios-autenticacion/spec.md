# usuarios-autenticacion Specification

## Purpose
MS6 registra usuarios, verifica credenciales, emite JWT y administra roles y estado de las cuentas (feature F12, US-12.3 a US-12.8). El API Gateway valida los tokens; este servicio recibe la identidad por `X-User-Id` y `X-User-Roles`.

## Status
- **Implementado y probado** (tests HTTP con MockMvc sobre H2 en modo PostgreSQL; 34 tests): todos los requisitos de esta spec.
- **No verificado automáticamente:** ejecución contra PostgreSQL real y flujo de punta a punta con el Gateway.

## Requirements

### Requirement: Registrarse como cliente (US-12.3)

MS6 SHALL crear con `POST /auth/registro` un usuario activo con rol `CLIENTE`, guardando la contraseña solo como hash BCrypt y devolviendo el perfil sin contraseña. SHALL ignorar cualquier rol o estado enviado. El correo SHALL normalizarse (sin espacios al borde, en minúsculas) y la base SHALL tener una restricción de unicidad sobre él. La contraseña SHALL tener entre 8 y 72 caracteres, con al menos una letra y un número.

#### Scenario: Registro válido
- **WHEN** se envían nombre, apellido, correo válido y contraseña válida
- **THEN** MS6 responde 201 con el usuario activo y roles `["CLIENTE"]`, sin contraseña ni hash

#### Scenario: Rol solicitado
- **WHEN** el pedido incluye `roles`, `rol` o `activo`
- **THEN** esos datos se ignoran y el usuario queda activo como `CLIENTE`

#### Scenario: Correo repetido
- **WHEN** el correo ya existe, aunque difiera en mayúsculas o espacios
- **THEN** MS6 responde 409 `CORREO_YA_REGISTRADO` y no crea un duplicado

#### Scenario: Registros simultáneos (reintento)
- **WHEN** llegan a la vez varios registros con el mismo correo
- **THEN** solo uno responde 201 y los demás 409

#### Scenario: Datos inválidos
- **WHEN** faltan datos o son inválidos
- **THEN** MS6 responde 400 `VALIDACION` con los campos inválidos en `campos`

### Requirement: Iniciar sesión y obtener un token (US-12.4)

MS6 SHALL emitir con `POST /auth/login` un JWT HS256 con `sub`, `email`, `roles`, `iss`, `iat` y `exp` (por defecto una hora), junto con `tipoToken` y `expiraEn` en segundos. El token SHALL NOT contener la contraseña ni otros datos sensibles. Cada intento SHALL registrarse en el log con el correo y el resultado, sin la contraseña.

#### Scenario: Credenciales válidas
- **WHEN** el correo y la contraseña son correctos y la cuenta está activa
- **THEN** MS6 responde 200 con el token y todos los roles del usuario en `roles`

#### Scenario: Credenciales inválidas
- **WHEN** el correo no existe o la contraseña es incorrecta
- **THEN** MS6 responde 401 `CREDENCIALES_INVALIDAS` con el mismo mensaje en ambos casos

#### Scenario: Usuario desactivado
- **WHEN** un usuario inactivo inicia sesión con credenciales correctas
- **THEN** MS6 responde 403 `USUARIO_INACTIVO` y no emite token

#### Scenario: Datos faltantes
- **WHEN** falta el correo o la contraseña
- **THEN** MS6 responde 400 `VALIDACION`

### Requirement: Consultar y actualizar el perfil (US-12.5)

MS6 SHALL devolver con `GET /usuarios/me` el perfil del usuario de `X-User-Id` y permitir con `PUT /usuarios/me` cambiar solo nombre y apellido.

#### Scenario: Sin identidad
- **WHEN** el pedido no trae `X-User-Id` válido
- **THEN** MS6 responde 401 `FALTA_IDENTIFICACION`

#### Scenario: Intento de cambiar roles, estado o correo
- **WHEN** `PUT /usuarios/me` incluye roles, estado o correo
- **THEN** solo cambian nombre y apellido

#### Scenario: Datos inválidos
- **WHEN** nombre o apellido faltan o son inválidos
- **THEN** MS6 responde 400 `VALIDACION`

### Requirement: Administrar usuarios y roles (US-12.6)

Solo un usuario con `ADMIN` en `X-User-Roles` SHALL listar usuarios (paginado, filtrable por rol y correo parcial), consultarlos y reemplazar sus roles con `PUT /usuarios/{id}/roles`. MS6 SHALL rechazar dejar el sistema sin administradores activos y SHALL registrar cada cambio de roles con el administrador y el `X-Correlation-Id`. Al primer arranque sin administradores activos SHALL crear el ADMIN inicial con `ADMIN_EMAIL` y `ADMIN_PASSWORD`; sin esas variables el servicio SHALL NOT arrancar.

#### Scenario: Sin rol ADMIN
- **WHEN** un usuario sin `ADMIN` lista, consulta o asigna roles
- **THEN** MS6 responde 403 `ACCESO_DENEGADO`

#### Scenario: Varios roles
- **WHEN** un administrador asigna `["ORGANIZADOR", "CLIENTE"]`
- **THEN** el usuario queda con ambos roles, que rigen desde su próximo login

#### Scenario: Último administrador
- **WHEN** el único administrador activo intenta quitarse el rol `ADMIN`
- **THEN** MS6 responde 409 `ULTIMO_ADMINISTRADOR` y no cambia los roles

#### Scenario: Rol o usuario inexistente
- **WHEN** se asigna un rol inexistente o a un usuario inexistente
- **THEN** MS6 responde 400 `VALIDACION` o 404 `USUARIO_NO_ENCONTRADO`

#### Scenario: Reinicio del servicio (idempotencia)
- **WHEN** el servicio arranca de nuevo con un administrador activo existente
- **THEN** no crea otro administrador inicial

### Requirement: Desactivar y reactivar usuarios (US-12.8)

Un administrador SHALL cambiar el estado con `PUT /usuarios/{id}/estado` sin borrar la cuenta.

#### Scenario: Desactivar y reactivar
- **WHEN** un administrador desactiva y luego reactiva un usuario
- **THEN** mientras está inactivo no puede iniciar sesión, y al reactivarlo vuelve a ingresar con su contraseña anterior

#### Scenario: Autodesactivación
- **WHEN** un administrador intenta desactivarse a sí mismo
- **THEN** MS6 responde 409 `AUTODESACTIVACION`

#### Scenario: Repetir la operación (idempotencia)
- **WHEN** se pide el estado que el usuario ya tiene
- **THEN** MS6 responde 200 sin cambios

### Requirement: Consultar usuarios desde otros servicios (US-12.7)

MS6 SHALL exponer `GET /internal/usuarios/{id}/contacto` (`{correo}`, también para cuentas inactivas) y `GET /internal/usuarios/por-correo?correo=` (`{id, roles, activo}`). Estas rutas SHALL NOT publicarse en el Gateway.

#### Scenario: Usuario inexistente
- **WHEN** el identificador o el correo no corresponde a ningún usuario
- **THEN** MS6 responde 404 `USUARIO_NO_ENCONTRADO`

#### Scenario: Usuario inactivo
- **WHEN** Notificaciones pide el contacto de un usuario inactivo
- **THEN** MS6 devuelve su correo

## Verification Evidence
- `AutenticacionTest` (11 tests), `UsuariosTest` (18) e `InicializacionTest` (5): `.\mvnw.cmd test`, sin Docker.
