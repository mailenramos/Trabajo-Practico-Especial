## ADDED Requirements

### Requirement: Identidad y roles desde el API Gateway
El servicio SHALL identificar al usuario con `X-User-Id` y obtener sus roles de `X-User-Roles`, una lista separada por coma que puede contener varios roles. El servicio SHALL comparar los roles sin distinguir mayúsculas ni espacios.

#### Scenario: Un solo rol
- **WHEN** llega `X-User-Id: u1` y `X-User-Roles: CLIENTE`
- **THEN** el usuario actual es `u1` con el conjunto de roles {CLIENTE}

#### Scenario: Varios roles
- **WHEN** llega `X-User-Roles: cliente, ADMIN`
- **THEN** el usuario actual tiene los roles {CLIENTE, ADMIN}

#### Scenario: Sin roles
- **WHEN** llega `X-User-Id` sin `X-User-Roles` ni `X-User-Role`
- **THEN** el usuario actual tiene un conjunto de roles vacío y la solicitud se procesa como la de un usuario sin privilegios

#### Scenario: Sin identificador
- **WHEN** falta `X-User-Id` o está en blanco
- **THEN** responde 401 con el código `USUARIO_NO_IDENTIFICADO`

#### Scenario: Encabezado anterior de un solo rol
- **WHEN** no llega `X-User-Roles` pero sí `X-User-Role: ADMIN`
- **THEN** el usuario actual tiene los roles {ADMIN}

#### Scenario: Los dos encabezados
- **WHEN** llegan `X-User-Roles: CLIENTE` y `X-User-Role: ADMIN`
- **THEN** el usuario actual tiene solo los roles {CLIENTE}

### Requirement: Permisos de administrador por pertenencia
Las operaciones reservadas a administradores SHALL permitirse cuando el conjunto de roles del usuario incluye el rol configurado en `compras.rol-administrador` (por defecto `ADMIN`).

#### Scenario: Administrador con otros roles
- **WHEN** un usuario con roles {CLIENTE, ADMIN} invoca una operación de administrador
- **THEN** la operación se procesa

#### Scenario: Usuario sin el rol de administrador
- **WHEN** un usuario con roles {CLIENTE} invoca una operación de administrador
- **THEN** responde 403 con el código `SOLO_ADMINISTRADOR`

#### Scenario: Rol configurado distinto
- **WHEN** `compras.rol-administrador` vale `ORGANIZADOR` y el usuario tiene el rol {ORGANIZADOR}
- **THEN** la operación se procesa

### Requirement: Titularidad sin cambios
Las operaciones sobre una compra o devolución SHALL seguir permitiéndose al titular identificado por `X-User-Id`, independientemente de sus roles.

#### Scenario: Titular con varios roles
- **WHEN** el titular de una compra, con roles {CLIENTE, PERSONAL_ACCESO}, consulta esa compra
- **THEN** la consulta se procesa

#### Scenario: Otro usuario
- **WHEN** un usuario que no es titular y no es administrador consulta la compra de otro
- **THEN** se mantiene la respuesta actual de rechazo
