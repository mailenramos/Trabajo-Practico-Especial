# lugares-y-sectores Specification

## Purpose
Esta capacidad define el registro, la configuración y la consulta de lugares físicos y sus sectores en TicketYa. Establece validaciones de datos, acceso administrativo a las operaciones de gestión y respuestas HTTP observables.

## Requirements

### Requirement: Registrar lugares con datos válidos
El servicio SHALL permitir registrar un lugar con nombre, dirección, latitud y longitud. El nombre y la dirección SHALL contener texto no vacío; la latitud SHALL estar entre -90 y 90 inclusive y la longitud entre -180 y 180 inclusive.

#### Scenario: Administrador registra un lugar válido
- **GIVEN** una solicitud autenticada con `X-User-Id` y `X-User-Role: administrador` y datos válidos
- **WHEN** envía la solicitud para registrar un lugar
- **THEN** el servicio crea el lugar y responde `201 Created` con su identificador y datos registrados

#### Scenario: Rechazar datos inválidos del lugar
- **GIVEN** una solicitud de registro con nombre o dirección vacíos, latitud fuera de -90..90 o longitud fuera de -180..180
- **WHEN** el servicio valida la solicitud
- **THEN** no crea el lugar y responde `400 Bad Request` indicando los campos inválidos

#### Scenario: Rechazar registro sin identidad
- **GIVEN** una solicitud de registro sin `X-User-Id` o sin `X-User-Role`
- **WHEN** solicita registrar un lugar
- **THEN** el servicio rechaza la solicitud con `401 Unauthorized` y no crea el lugar

#### Scenario: Rechazar registro por rol no administrador
- **GIVEN** una solicitud de registro autenticada cuyo `X-User-Role` es `cliente` u `organizador`
- **WHEN** solicita registrar un lugar
- **THEN** el servicio rechaza la solicitud con `403 Forbidden` y no crea el lugar

### Requirement: Registrar sectores numerados
El servicio SHALL permitir que un administrador agregue a un lugar existente un sector numerado configurando F filas y A asientos por fila, con F y A mayores que cero. Cada asiento SHALL quedar identificado por su fila y número dentro del sector, sin duplicar la combinación fila+número.

#### Scenario: Crear sector numerado válido
- **GIVEN** un lugar existente y una solicitud autenticada como administrador con F y A mayores que cero
- **WHEN** agrega un sector numerado al lugar
- **THEN** queda definida la estructura de F × A asientos identificados por fila y número, y el servicio responde `201 Created`

#### Scenario: Rechazar configuración numerada inválida
- **GIVEN** un lugar existente y una solicitud autenticada como administrador con F o A menor o igual que cero
- **WHEN** agrega un sector numerado
- **THEN** el servicio no crea el sector y responde `400 Bad Request`

#### Scenario: Unicidad de fila y número dentro del sector
- **GIVEN** un sector numerado con F filas y A asientos por fila
- **WHEN** se identifica cualquier asiento del sector mediante una fila y un número
- **THEN** queda identificado por un par `(fila, número)` único, con `1 <= fila <= F` y `1 <= número <= A`

#### Scenario: No agregar sector a un lugar inexistente
- **GIVEN** una solicitud autenticada como administrador que referencia un lugar inexistente
- **WHEN** agrega un sector numerado
- **THEN** el servicio responde `404 Not Found` y no crea el sector

#### Scenario: Solo administrador puede agregar sectores numerados
- **GIVEN** una solicitud para agregar un sector numerado sin identidad, o con rol distinto de `administrador`
- **WHEN** el servicio procesa la solicitud
- **THEN** responde `401 Unauthorized` si falta `X-User-Id` o `X-User-Role`, o `403 Forbidden` si el rol autenticado no es administrador, y no crea el sector

### Requirement: Registrar sectores no numerados
El servicio SHALL permitir que un administrador agregue a un lugar existente un sector no numerado con capacidad entera mayor que cero.

#### Scenario: Crear sector no numerado válido
- **GIVEN** un lugar existente y una solicitud autenticada como administrador con capacidad mayor que cero
- **WHEN** agrega un sector no numerado
- **THEN** el servicio crea el sector con esa capacidad y responde `201 Created`

#### Scenario: Rechazar capacidad no numerada inválida
- **GIVEN** una solicitud autenticada como administrador con capacidad cero, negativa o no entera
- **WHEN** agrega un sector no numerado
- **THEN** el servicio no crea el sector y responde `400 Bad Request`

#### Scenario: No agregar sector a un lugar inexistente
- **GIVEN** una solicitud autenticada como administrador que referencia un lugar inexistente
- **WHEN** agrega un sector no numerado
- **THEN** el servicio responde `404 Not Found` y no crea el sector

#### Scenario: Solo administrador puede agregar sectores no numerados
- **GIVEN** una solicitud para agregar un sector no numerado sin identidad, o con rol distinto de `administrador`
- **WHEN** el servicio procesa la solicitud
- **THEN** responde `401 Unauthorized` si falta `X-User-Id` o `X-User-Role`, o `403 Forbidden` si el rol autenticado no es administrador, y no crea el sector

### Requirement: Consultar lugares y sectores
El servicio SHALL permitir que usuarios con rol `organizador` o `administrador` consulten los lugares registrados y el detalle de un lugar con sus sectores y la configuración correspondiente. La solicitud SHALL incluir `X-User-Id` y `X-User-Role`; si falta alguno responderá `401 Unauthorized`, y si el rol es `cliente` responderá `403 Forbidden`. La consulta SHALL responder `404 Not Found` cuando el identificador del lugar no exista.

#### Scenario: Consultar colección de lugares
- **GIVEN** que existen cero o más lugares registrados y la solicitud tiene identidad de organizador o administrador
- **WHEN** un consumidor consulta los lugares
- **THEN** el servicio responde `200 OK` con la colección de lugares, que puede estar vacía

#### Scenario: Consultar lugar existente con sus sectores
- **GIVEN** un lugar registrado con sus sectores y la solicitud tiene identidad de organizador o administrador
- **WHEN** un consumidor consulta el lugar por identificador
- **THEN** el servicio responde `200 OK` con los datos del lugar, sus sectores y la configuración de asientos o capacidad de cada sector

#### Scenario: Consultar lugar inexistente
- **GIVEN** un identificador de lugar no registrado y la solicitud tiene identidad de organizador o administrador
- **WHEN** un consumidor consulta el detalle del lugar o sus sectores
- **THEN** el servicio responde `404 Not Found`

#### Scenario: Rechazar consulta sin identidad
- **GIVEN** una solicitud GET de lugares o sectores sin `X-User-Id` o sin `X-User-Role`
- **WHEN** consulta la colección o un recurso
- **THEN** el servicio responde `401 Unauthorized`

#### Scenario: Rechazar consulta de un cliente
- **GIVEN** una solicitud GET con `X-User-Id` y `X-User-Role: cliente`
- **WHEN** consulta la colección de lugares o un lugar con sus sectores
- **THEN** el servicio responde `403 Forbidden`
