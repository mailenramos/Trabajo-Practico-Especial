## ADDED Requirements

### Requirement: Identificar solicitudes HTTP
MS1 SHALL recibir `X-Correlation-Id` y conservarlo únicamente si contiene entre 1 y 100 letras ASCII, números, guiones, guiones bajos o puntos; si falta o es inválido SHALL generar un UUID. SHALL colocar el ID en MDC con clave `correlationId`, devolverlo en `X-Correlation-Id` en la respuesta y limpiar esa clave al terminar la solicitud.

#### Scenario: Propagar el identificador recibido
- **GIVEN** una solicitud con `X-Correlation-Id` válido
- **WHEN** MS1 procesa la solicitud
- **THEN** usa ese mismo ID en el MDC y lo devuelve en el header de respuesta

#### Scenario: Generar identificador cuando falta o está vacío
- **GIVEN** una solicitud sin `X-Correlation-Id` o con valor vacío o solo espacios
- **WHEN** MS1 procesa la solicitud
- **THEN** genera un UUID, lo usa como `correlationId` y lo incluye en la respuesta

#### Scenario: Rechazar identificador inválido
- **GIVEN** un `X-Correlation-Id` de más de 100 caracteres o con caracteres fuera de letras ASCII, números, guiones, guiones bajos y puntos
- **WHEN** MS1 procesa la solicitud
- **THEN** descarta el valor y genera un UUID para el contexto y la respuesta

#### Scenario: Devolver identificador en un error
- **GIVEN** una solicitud que produce una respuesta de error
- **WHEN** MS1 finaliza su procesamiento
- **THEN** la respuesta incluye el ID de correlación y el MDC no queda asociado al hilo para la siguiente solicitud

### Requirement: Registrar solicitudes sin revelar secretos
MS1 SHALL emitir en stdout un log por solicitud HTTP excepto `/actuator/**`, con método, ruta sin query string, `X-User-Id` si existe, código de respuesta y duración en milisegundos. El patrón Logback SHALL incluir `correlationId`. SHALL NOT registrar tokens de usuario, `X-Service-Token`, cuerpos ni contraseñas.

#### Scenario: Excluir actuator del log de acceso
- **GIVEN** una solicitud a `/actuator/health` u otra ruta `/actuator/**`
- **WHEN** MS1 responde
- **THEN** no emite un log de acceso para esa solicitud

#### Scenario: Log de solicitud con los campos requeridos
- **GIVEN** una solicitud con usuario, ruta, parámetros de consulta e ID de correlación
- **WHEN** MS1 responde
- **THEN** un único log contiene método, ruta sin query, usuario, código, duración en ms y el ID de correlación

#### Scenario: No registrar secretos
- **GIVEN** una solicitud o llamada saliente con tokens, contraseña y cuerpo, incluso ante error
- **WHEN** MS1 registra su procesamiento
- **THEN** ningún valor secreto, cuerpo ni query string aparece en los logs

### Requirement: Propagar correlación en llamadas salientes
MS1 SHALL enviar `X-Correlation-Id` en todas sus llamadas HTTP salientes, incluidas las operaciones de Reservas y de la outbox. El cliente MS6 SHALL usar el mismo mecanismo cuando exista. Si una operación sin solicitud HTTP necesita hacer una llamada, SHALL generar un ID para su contexto en lugar de enviar un header vacío.

#### Scenario: Propagar el ID recibido a Reservas
- **GIVEN** una solicitud con un `X-Correlation-Id` y una operación que llama a Reservas
- **WHEN** MS1 envía la llamada saliente
- **THEN** el header `X-Correlation-Id` contiene el mismo valor

#### Scenario: Correlacionar una llamada desde un scheduler
- **GIVEN** una operación programada sin contexto de solicitud
- **WHEN** MS1 realiza una llamada HTTP saliente
- **THEN** genera un ID no vacío, lo propaga y limpia su contexto al finalizar

### Requirement: Conservar correlación en la outbox
MS1 SHALL guardar `correlationId` como campo opcional del aviso outbox al crearlo cuando exista contexto. El dispatcher SHALL usar el ID guardado en cada entrega y reintento y enviarlo en `X-Correlation-Id`; un aviso antiguo sin ID SHALL recibir un UUID nuevo al despacharse. SHALL conservar `X-Aviso-Id`, destinos, payload, estados y política de reintentos.

#### Scenario: Reenviar el ID guardado en entrega y reintento
- **GIVEN** una solicitud con ID de correlación que crea un aviso outbox
- **WHEN** se entrega o reintenta el aviso desde el scheduler
- **THEN** cada llamada envía el mismo `X-Correlation-Id` guardado y mantiene el `X-Aviso-Id` original

#### Scenario: Despachar un aviso antiguo sin ID
- **GIVEN** un aviso guardado antes de este cambio sin `correlationId`
- **WHEN** el dispatcher lo procesa
- **THEN** genera un UUID para ese despacho y lo envía en `X-Correlation-Id`

#### Scenario: No mezclar contexto entre avisos
- **GIVEN** dos avisos con diferentes identificadores o una entrega inmediata dentro de una solicitud
- **WHEN** el dispatcher procesa cada aviso, incluso si ocurre un error
- **THEN** usa el ID correspondiente y restaura el MDC previo al finalizar
