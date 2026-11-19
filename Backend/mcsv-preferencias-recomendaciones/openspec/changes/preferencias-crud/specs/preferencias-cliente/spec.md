## Purpose

Permite que cada cliente autenticado consulte y mantenga sus propios intereses en tipos de evento, artistas y ciudades. La capacidad constituye la base de datos de preferencias de MS8, sin incluir recomendaciones.

## ADDED Requirements

### Requirement: Proteger los endpoints de preferencias con el rol CLIENTE
Las rutas `GET`, `PUT` y `DELETE /preferencias/me` SHALL requerir ambos headers, `X-User-Id` y `X-User-Roles`, además del rol `CLIENTE`. Los roles SHALL interpretarse como una lista separada por comas, recortando espacios y sin distinguir mayúsculas. Si falta cualquiera de los headers, el servicio SHALL responder `401`; SHALL responder `403` solo cuando ambos están presentes y la lista no incluye `CLIENTE`.

#### Scenario: Falta cualquiera de los headers en cada endpoint
- **WHEN** una solicitud a cualquiera de los tres endpoints no incluye `X-User-Id` o no incluye `X-User-Roles`
- **THEN** el servicio responde `401 Unauthorized`

#### Scenario: Rol distinto de CLIENTE en cada endpoint
- **GIVEN** la solicitud incluye ambos headers y `X-User-Roles` no contiene `CLIENTE`
- **WHEN** se invoca cualquiera de los tres endpoints
- **THEN** el servicio responde `403 Forbidden`

#### Scenario: Rol CLIENTE con espacios y mayúsculas distintas
- **GIVEN** `X-User-Id` identifica al cliente y `X-User-Roles` contiene `organizador, cliente`
- **WHEN** el cliente invoca un endpoint de preferencias
- **THEN** el servicio reconoce `CLIENTE` y procesa la solicitud

### Requirement: Consultar las preferencias propias
El servicio SHALL devolver las preferencias asociadas al `X-User-Id` autenticado, sin permitir consultar preferencias de otro usuario.

#### Scenario: Consultar preferencias existentes
- **GIVEN** existe un documento de preferencias para el usuario autenticado
- **WHEN** el cliente solicita `GET /preferencias/me`
- **THEN** el servicio responde `200 OK` con `tiposEvento`, `artistas`, `ciudades` y `actualizadoEn` guardados

#### Scenario: Consultar preferencias aún inexistentes
- **GIVEN** no existe un documento para el usuario autenticado
- **WHEN** el cliente solicita `GET /preferencias/me`
- **THEN** el servicio responde `200 OK` con `tiposEvento`, `artistas` y `ciudades` como listas vacías y `actualizadoEn` como `null`

### Requirement: Reemplazar las preferencias propias
El servicio SHALL aceptar en `PUT /preferencias/me` las listas `tiposEvento`, `artistas` y `ciudades`, sustituir el conjunto previo y responder `200 OK` con el estado guardado.

#### Scenario: Guardar y reemplazar preferencias
- **GIVEN** el cliente envía las tres listas, que pueden estar vacías
- **WHEN** solicita `PUT /preferencias/me`
- **THEN** el servicio guarda las nuevas listas para el `X-User-Id`, actualiza `actualizadoEn` y devuelve el documento guardado

#### Scenario: Recortar elementos antes de guardar
- **GIVEN** el cliente envía valores con espacios iniciales o finales
- **WHEN** solicita `PUT /preferencias/me`
- **THEN** el servicio guarda y devuelve cada valor sin esos espacios

#### Scenario: Rechazar elementos vacíos
- **GIVEN** alguna lista contiene un texto vacío o compuesto solo por espacios
- **WHEN** el cliente solicita `PUT /preferencias/me`
- **THEN** el servicio responde `400 Bad Request` y no reemplaza las preferencias existentes

#### Scenario: Rechazar valores repetidos después de recortar
- **GIVEN** alguna lista contiene el mismo texto más de una vez, incluso si una aparición tiene espacios exteriores
- **WHEN** el cliente solicita `PUT /preferencias/me`
- **THEN** el servicio responde `400 Bad Request` y no reemplaza las preferencias existentes

#### Scenario: Rechazar una lista ausente o nula
- **GIVEN** el cliente tiene preferencias guardadas y el cuerpo omite una de las listas o la envía como `null`
- **WHEN** solicita `PUT /preferencias/me`
- **THEN** el servicio responde `400 Bad Request` y no reemplaza las preferencias existentes

### Requirement: Eliminar las preferencias propias
El servicio SHALL eliminar únicamente las preferencias asociadas al `X-User-Id` autenticado y responder sin contenido.

#### Scenario: Eliminar preferencias existentes
- **GIVEN** existe un documento de preferencias para el usuario autenticado
- **WHEN** el cliente solicita `DELETE /preferencias/me`
- **THEN** el servicio elimina el documento y responde `204 No Content`

#### Scenario: Eliminar preferencias inexistentes
- **GIVEN** no existe un documento para el usuario autenticado
- **WHEN** el cliente solicita `DELETE /preferencias/me`
- **THEN** el servicio responde `204 No Content` sin crear ni modificar documentos
