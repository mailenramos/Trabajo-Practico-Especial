# avisos-eventos-interes Specification

## Purpose
Avisar mediante Notificaciones a los clientes cuyos artistas y ciudades preferidos coinciden con un evento publicado por MS1.

## Requirements

### Requirement: Proteger y validar el callback de publicaciones
`POST /internal/preferencias/eventos-publicados` SHALL requerir `X-Service-Token` que coincida con `SERVICIO_TOKEN`. Si la variable no está configurada o está en blanco, MS8 SHALL rechazar todas las solicitudes con `403 Forbidden`. El endpoint SHALL no requerir headers de identidad de usuario ni exponerse por el Gateway.

#### Scenario: Token ausente o incorrecto
- **GIVEN** `SERVICIO_TOKEN` está configurada
- **WHEN** la solicitud omite `X-Service-Token` o presenta un valor diferente
- **THEN** MS8 responde `403 Forbidden` sin procesar el evento

#### Scenario: Token de MS8 no configurado
- **GIVEN** `SERVICIO_TOKEN` está ausente o en blanco en MS8
- **WHEN** llega cualquier solicitud al callback, incluso si trae un header `X-Service-Token`
- **THEN** MS8 responde `403 Forbidden` y no acepta el callback

#### Scenario: Callback válido sin identidad de usuario
- **GIVEN** el header de servicio coincide y el body del evento es válido
- **WHEN** MS1 invoca el endpoint sin `X-User-Id` ni `X-User-Roles`
- **THEN** MS8 procesa la publicación

#### Scenario: Body inválido
- **GIVEN** el token de servicio es válido
- **WHEN** faltan campos requeridos, `participantes` no es una lista de textos, o `fechaHora` no puede parsearse como el instante UTC enviado por MS1
- **THEN** MS8 responde `400 Bad Request` con el formato de error estándar

#### Scenario: Fecha en el formato real de MS1
- **GIVEN** el token y los demás campos son válidos y `fechaHora` es `2030-01-02T03:04:05Z`, como en la outbox de MS1
- **WHEN** MS8 recibe el callback
- **THEN** acepta la fecha como instante UTC y no la rechaza con `400`

#### Scenario: Fecha ISO sin zona
- **GIVEN** `fechaHora` es un timestamp ISO local sin zona y los demás campos son válidos
- **WHEN** MS8 interpreta la fecha
- **THEN** la considera en `America/Argentina/Buenos_Aires`

### Requirement: Seleccionar una sola vez a cada cliente interesado
Un usuario SHALL ser destinatario solo si al menos un valor de `artistas` coincide con un elemento de `participantes` y al menos un valor de `ciudades` coincide con `ciudad`. Las comparaciones SHALL recortar espacios exteriores y no distinguir mayúsculas usando `Locale.ROOT`. MS8 SHALL ignorar `tiposEvento` y enviar como máximo un aviso por usuario y evento.

#### Scenario: Coincide solo el artista
- **GIVEN** un artista de las preferencias del usuario coincide con un participante pero ninguna ciudad coincide
- **WHEN** MS8 selecciona destinatarios
- **THEN** el usuario no es destinatario

#### Scenario: Coincide solo la ciudad
- **GIVEN** una ciudad de las preferencias del usuario coincide con la ciudad del evento pero ningún artista coincide
- **WHEN** MS8 selecciona destinatarios
- **THEN** el usuario no es destinatario

#### Scenario: Coinciden artista y ciudad, un solo aviso
- **GIVEN** las preferencias de un usuario coinciden tanto por artista como por ciudad
- **WHEN** MS8 selecciona destinatarios
- **THEN** el usuario aparece una sola vez y recibe un solo intento de aviso para el evento

#### Scenario: Coincidencia normalizada
- **GIVEN** artista y participante coinciden, y ciudad del usuario y del evento coinciden, salvo por mayúsculas o espacios exteriores
- **WHEN** MS8 compara preferencias con el evento
- **THEN** considera que ambos criterios coinciden y selecciona al usuario una sola vez

#### Scenario: Sin coincidencias
- **GIVEN** ningún usuario cumple ambas condiciones de artista y ciudad
- **WHEN** MS8 procesa la publicación
- **THEN** responde `202 Accepted` con `destinatarios: 0` y `enviados: 0`

#### Scenario: Lista de participantes vacía
- **GIVEN** el evento tiene una lista de participantes vacía aunque haya ciudades coincidentes
- **WHEN** MS8 selecciona destinatarios
- **THEN** no selecciona usuarios ni envía avisos

### Requirement: Enviar avisos con el contrato de Notificaciones
Por cada destinatario distinto, MS8 SHALL enviar un `POST {NOTIFICACIONES_BASE_URL}/internal/notificaciones` con `X-Service-Token` y `Content-Type: application/json`. El JSON SHALL incluir `acontecimientoId`, `tipo=NUEVO_EVENTO_DE_INTERES`, `versionEsquema=1`, `ocurridoEn`, `recursoId`, `usuarioId`, `correlacionId` y los datos `evento`, `fecha` y `lugar`.

#### Scenario: Envío con el payload acordado
- **GIVEN** una publicación válida tiene al menos un destinatario
- **WHEN** Notificaciones acepta el aviso
- **THEN** MS8 envía al usuario un JSON con los campos y valores del contrato y el token de servicio en el header

### Requirement: Formatear datos temporales y correlación del aviso
`ocurridoEn` SHALL representar el instante actual en UTC. `datos.fecha` SHALL derivarse de `fechaHora` usando `dd/MM/yyyy HH:mm` en `America/Argentina/Buenos_Aires`; `datos.evento` SHALL ser `nombre` y `datos.lugar` SHALL ser `ciudad`. Si `X-Aviso-Id` tiene contenido, se SHALL usar como `correlacionId`; de lo contrario MS8 SHALL generar un UUID por callback.

#### Scenario: Correlación recibida
- **GIVEN** la solicitud incluye un `X-Aviso-Id` no vacío
- **WHEN** MS8 construye los avisos
- **THEN** usa ese valor como `correlacionId` para los destinatarios

#### Scenario: Correlación generada si falta el header
- **GIVEN** la solicitud no incluye `X-Aviso-Id` o está en blanco
- **WHEN** MS8 construye los avisos
- **THEN** genera un UUID de correlación y lo reutiliza para el callback

### Requirement: Mantener un identificador de acontecimiento deduplicable
MS8 SHALL formar `acontecimientoId` como `publicacion-{eventoId}-{usuarioId}` y SHALL limitarlo a un máximo de 100 caracteres sin truncar ni cambiar el formato.

#### Scenario: Identificador de acontecimiento excede el máximo
- **GIVEN** `publicacion-{eventoId}-{usuarioId}` supera 100 caracteres para un destinatario
- **WHEN** MS8 intenta construir su aviso
- **THEN** cuenta ese destinatario como fallo de entrega, continúa con los demás y responde `503 Service Unavailable`

### Requirement: Completar los intentos y comunicar fallos de entrega
MS8 SHALL procesar los destinatarios secuencialmente e intentar el envío a todos aunque uno falle. Solo `202 Accepted` de Notificaciones SHALL contar como envío aceptado. Sin fallos, MS8 SHALL responder `202` con `{ "destinatarios": n, "enviados": m }`; si cualquier envío falla con `4xx`, `5xx`, timeout u otro error de transporte, SHALL responder `503 Service Unavailable` después de intentar a los demás. MS8 SHALL registrar la cantidad de fallos sin registrar el token.

#### Scenario: Todos los avisos aceptados
- **GIVEN** Notificaciones acepta todos los avisos o no hay destinatarios
- **WHEN** MS8 termina el fan-out
- **THEN** responde `202 Accepted` con los contadores de destinatarios y envíos aceptados

#### Scenario: Un destinatario falla y se intenta a los demás
- **GIVEN** falla la entrega a uno de varios destinatarios
- **WHEN** MS8 procesa la publicación
- **THEN** intenta los avisos restantes, registra el número de fallos sin el token y responde `503 Service Unavailable`

#### Scenario: Reintento de outbox tras fallo parcial
- **GIVEN** algunos avisos fueron aceptados y otro envío falló
- **WHEN** MS1 reintenta el mismo callback
- **THEN** MS8 vuelve a intentar los destinatarios y Notificaciones puede deduplicar los avisos ya aceptados con `acontecimientoId`, `tipo` y `usuarioId`
