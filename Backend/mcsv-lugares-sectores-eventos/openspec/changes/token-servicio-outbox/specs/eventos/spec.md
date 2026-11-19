## ADDED Requirements

### Requirement: Autenticar las entregas salientes de outbox
MS1 SHALL enviar `X-Service-Token` con el valor configurado de `servicio.token` en todos los POST salientes de outbox, además de conservar `X-Aviso-Id`. El token SHALL provenir de `SERVICIO_TOKEN` sin valor predeterminado secreto. MS1 SHALL NOT registrar ni persistir el valor del token.

#### Scenario: Enviar el token junto al identificador estable
- **GIVEN** `SERVICIO_TOKEN` contiene un valor no vacío y hay un aviso listo para entregar
- **WHEN** la outbox envía o reenvía el aviso por HTTP
- **THEN** incluye el valor configurado en `X-Service-Token` y el mismo ID estable en `X-Aviso-Id`, sin alterar el destino ni el payload

#### Scenario: Usar el token para todos los tipos de aviso
- **GIVEN** hay avisos de publicación y cancelación listos para entregar y un token configurado
- **WHEN** el cliente común de outbox los envía
- **THEN** cada solicitud incluye `X-Service-Token` independientemente del tipo de aviso

#### Scenario: No revelar el token
- **GIVEN** hay un token configurado y un aviso se entrega o falla
- **WHEN** MS1 registra el resultado
- **THEN** el valor del token no aparece en logs ni en `ultimoError` ni se persiste en el aviso

### Requirement: Validar el token de outbox según el perfil
MS1 SHALL fallar al arrancar con perfil `docker` activo si `servicio.token` está vacío o contiene solo espacios. En otros perfiles SHALL permitir el arranque y emitir un `WARN` sin revelar el valor. Compose SHALL pasar `SERVICIO_TOKEN` al servicio `lugares` sin default.

#### Scenario: Bloquear arranque Docker sin token
- **GIVEN** el perfil `docker` está activo, solo o con otros perfiles, y el token está vacío o contiene solo espacios
- **WHEN** MS1 inicia
- **THEN** falla el arranque con el mensaje `SERVICIO_TOKEN no configurada`

#### Scenario: Advertir sin bloquear otros perfiles
- **GIVEN** el perfil `docker` no está activo y el token está vacío o contiene solo espacios
- **WHEN** MS1 inicia
- **THEN** el arranque continúa y se registra un `WARN` sin exponer el token

## MODIFIED Requirements

### Requirement: Entregar avisos con reintentos limitados
El dispatcher SHALL enviar avisos mediante `POST` fuera de la transacción con timeouts de conexión/lectura de `2 s`/`5 s`; cualquier `2xx` marca `ENTREGADO`. Reintenta timeouts, errores de conexión, `5xx`, `408` y `429`; el resto de `4xx` y las respuestas `3xx` son definitivos. El máximo es 8 reclamos con backoff de 30 s × 2, tope 15 min y sin jitter; el polling es configurable y el lease fijo de 60 s.

#### Scenario: Entregar al destino fuera de la transacción
- **GIVEN** un cambio de evento y su aviso fueron confirmados por MongoDB
- **WHEN** el dispatcher envía el aviso por HTTP
- **THEN** la llamada remota usa los timeouts establecidos y no forma parte de la transacción MongoDB

#### Scenario: Sin token no se procesan avisos
- **GIVEN** el perfil `docker` no está activo, no hay token configurado y existe un aviso `PENDIENTE`
- **WHEN** el scheduler o la entrega inmediata intenta procesarlo
- **THEN** no hay barrido, reclamo ni HTTP y el aviso permanece `PENDIENTE`, sin consumir intentos ni modificar `ultimoError`; una vez configurado el token, se entrega en el siguiente intento elegible

#### Scenario: No reintentar un error 4xx definitivo
- **GIVEN** un aviso pendiente y un destino que responde con un `4xx` distinto de `408` o `429`
- **WHEN** el dispatcher registra la respuesta
- **THEN** el aviso pasa a `ERROR_FINAL`, conserva el error y no se reintenta

#### Scenario: No reintentar una respuesta 3xx
- **GIVEN** un aviso pendiente y un destino que responde con una redirección `3xx`
- **WHEN** el dispatcher registra la respuesta
- **THEN** el aviso pasa a `ERROR_FINAL`, conserva el error y no se reintenta

#### Scenario: Reintentar errores temporales de entrega
- **GIVEN** un aviso pendiente y un destino que responde con `5xx`, `408` o `429`, no responde antes del timeout o no está disponible por un error de conexión
- **WHEN** el dispatcher registra el fallo antes de agotar los intentos
- **THEN** el aviso registra intentos y último error, vuelve a quedar pendiente con próximo intento según la espera progresiva y el cambio original del evento permanece confirmado

#### Scenario: Barrer avisos con intentos agotados antes de entregarlos
- **GIVEN** un token configurado y un aviso `PENDIENTE` con 8 intentos o `PROCESANDO` con el lease vencido y 8 intentos
- **WHEN** comienza una pasada del scheduler
- **THEN** el aviso pasa a `ERROR_FINAL` con último error `intentos agotados`, se limpia su lease si lo tenía y no se entrega en esa pasada ni vuelve a reclamarse automáticamente

#### Scenario: Agotar los intentos durante una entrega fallida
- **GIVEN** un aviso que alcanza el límite de 8 intentos o recibe un error definitivo
- **WHEN** el dispatcher registra el resultado
- **THEN** pasa a `ERROR_FINAL`, conserva el último error para revisión y no vuelve a seleccionarlo automáticamente
