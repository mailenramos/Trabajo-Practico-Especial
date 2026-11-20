# Gestión de lugares, sectores y eventos

Microservicio Spring Boot de TicketYa. Gestiona lugares físicos, sus sectores y el ciclo de vida de eventos. Persiste en MongoDB en colecciones propias; la comunicación entre servicios usa IDs y llamadas directas.

## Requisitos

- JDK 21 o superior.
- Maven 3.9 o superior.
- Docker Desktop con Docker Compose para MongoDB y las pruebas de integración.
- Node.js (opcional, para simular Reservas, Compras, Fila, Usuarios o Preferencias desde Postman).

## Ejecutar MS1 con Docker Compose

Ejecutá estos pasos desde `mcsv-lugares-sectores-eventos`.

Antes de iniciar Compose, creá la red externa compartida (solo hace falta una vez por entorno Docker):

```sh
docker network create ticketya-net
```

Configurá `SERVICIO_TOKEN` en el entorno o en un archivo `.env` local ignorado por Git, con el valor compartido por los servicios receptores. El perfil `docker` no arranca si falta o contiene solo espacios.

Para levantar MS1 y MongoDB aislados, sin publicar puertos en el host:

```sh
docker compose up -d --build
```

Para desarrollo local con puertos publicados, combiná explícitamente el Compose base y el archivo dev:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

El archivo dev publica la aplicación en `8081:8081` y MongoDB solo en `127.0.0.1:27017:27017`. El Compose base no publica puertos. Los datos de MongoDB persisten en el volumen `mongodb_data`.

### Integración con el Compose general

La red compartida `ticketya-net` es externa y se crea una sola vez por entorno Docker:

```sh
docker network create ticketya-net
```

La topología de MS1 y los servicios confirmados por el grupo es:

- `lugares` escucha en el puerto interno `8081`, no publica puertos en el Compose base y se une a la red privada de este Compose y a `ticketya-net`.
- `mongodb` permanece únicamente en la red privada de este Compose; **no** se une a `ticketya-net`.
- Reservas llama a MS1 directamente por `lugares:8081` y Compras también se conecta directamente por el nombre del servicio, sin pasar por el Gateway.
- Servicios y puertos: Reservas `reservas:8080`, Compras `compras:8083`, Fila virtual `fila-virtual:8088`, Gateway `gateway:8086`, Pagos `pagos:8084` y Entradas `entradas:8085`.
- El Gateway llega a MS1 mediante `MS1_URL=http://lugares:8081`; no enruta `/actuator` ni `/internal`.

El Compose general debe ubicarse en la raíz del repositorio, pero todavía no existe. El comando exacto para combinarlo con el Compose de MS1 queda **pendiente hasta que se cree ese archivo**.

### Ejecutar MS1 desde el host

Para desarrollar con `mvn spring-boot:run`, levantá MongoDB con el archivo dev, que publica el puerto solo en loopback:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mongodb
```

Después, desde esta carpeta, iniciá MS1 en el host:

```sh
mvn spring-boot:run
```

El default de host de `MONGODB_URI` es `mongodb://localhost:27017/ticketya_lugares?directConnection=true`, compatible con el replica set anunciado dentro de Docker como `mongodb:27017`. El perfil local predeterminado es `dev` y carga datos de ejemplo; en Docker el perfil predeterminado es `docker`. La aplicación escucha en `http://localhost:8081`; la documentación interactiva está en <http://localhost:8081/swagger-ui/index.html> y el documento OpenAPI en <http://localhost:8081/v3/api-docs>.

Para detener los servicios sin borrar sus datos:

```sh
docker compose down
```

**Migración de un volumen legacy:** si el volumen existente tiene `rs0` configurado con `localhost:27017`, es necesario reinicializarlo para que anuncie `mongodb:27017`. `docker compose down -v` elimina permanentemente el volumen y todos sus datos. Exportá o respaldá los datos que necesites antes de ejecutar ese comando.

Consultá lugares con headers de identidad cuando el servicio esté disponible en el host:

```sh
curl.exe -H "X-User-Id: organizador-demo" -H "X-User-Roles: ORGANIZADOR" http://localhost:8081/lugares
```

## Endpoints

El servicio local usa `http://localhost:8081` por defecto. Las operaciones protegidas requieren `X-User-Id` y `X-User-Roles`; el Gateway deriva el ID del claim `sub` del JWT y envía la lista de roles separada por comas. El servicio reconoce cada rol por separado, sin elegir uno arbitrariamente, compara sin distinguir mayúsculas y recorta espacios. La búsqueda y el detalle de eventos publicados o agotados son públicos. Los endpoints internos de callback y consulta entre servicios no exigen headers de usuario.

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| POST | `/lugares` | `ADMIN` | Registra un lugar. |
| GET | `/lugares` | `ORGANIZADOR` o `ADMIN` | Lista lugares. |
| GET | `/lugares/{id}` | `ORGANIZADOR` o `ADMIN` | Consulta un lugar y sus sectores. |
| GET | `/lugares/{id}/sectores` | `ORGANIZADOR` o `ADMIN` | Lista los sectores de un lugar. |
| POST | `/lugares/{id}/sectores` | `ADMIN` | Agrega un sector numerado o no numerado. |
| POST | `/eventos` | `ORGANIZADOR` | Crea un evento en borrador. |
| GET | `/eventos` | Público, sin token | Busca eventos publicados y agotados con `tipo`, `organizadorId`, `participante`, `ciudad`, `fechaDesde`, `fechaHasta`, `orden`, `direccion`, `q`, `page` y `size`. |
| GET | `/eventos/{eventoId}` | Público para `publicado`/`agotado`; borrador requiere autenticación | Solo el organizador titular o `ADMIN` puede consultar un borrador; otros usuarios reciben `404`. |
| PUT | `/eventos/{eventoId}/sectores/{sectorId}/precio` | `ORGANIZADOR` dueño | Define el precio de un sector del evento en borrador. |
| PUT | `/eventos/{eventoId}/limite-entradas-por-cliente` | `ORGANIZADOR` dueño | Actualiza el límite por cliente durante el borrador. |
| PUT | `/eventos/{eventoId}/publicar` | `ORGANIZADOR` dueño | Inicializa la disponibilidad con Reservas y publica el evento. |
| PUT | `/eventos/{eventoId}/venta/suspender` | `ADMIN` | Suspende la venta sin crear un estado formal nuevo. |
| PUT | `/eventos/{eventoId}/venta/reactivar` | `ADMIN` | Reactiva la venta. |
| PUT | `/eventos/{eventoId}/cancelar` | `ADMIN` | Cancela el evento después de confirmarlo con Reservas; body opcional `{"motivo":"..."}`. |
| POST | `/eventos/{eventoId}/personal-acceso` | `ORGANIZADOR` dueño o `ADMIN` | Asigna un usuario activo con rol `PERSONAL_ACCESO`, consultado en MS6 por correo. |
| GET | `/eventos/{eventoId}/personal-acceso` | `ORGANIZADOR` dueño o `ADMIN` | Lista las asignaciones activas y revocadas. |
| DELETE | `/eventos/{eventoId}/personal-acceso/{usuarioId}` | `ORGANIZADOR` dueño o `ADMIN` | Revoca una asignación de forma idempotente. |
| PUT | `/eventos/{eventoId}/disponibilidad` | Interno: Reservas | Recibe el aviso de agotado o recuperación. No exige `X-User-Id` ni `X-User-Roles`; no debe exponerse por el API Gateway. |
| GET | `/internal/eventos/{eventoId}` | Interno: Compras y otros servicios | Consulta datos actuales del evento en cualquier estado. No exige headers de usuario y no debe exponerse por el API Gateway. |
| PUT | `/internal/eventos/{eventoId}/politica-fila` | Interno: Fila virtual (MS7) | Activa o desactiva `requiereFila`; no exige headers de usuario ni se expone por el API Gateway. |
| GET | `/internal/eventos-publicados` | Interno: Preferencias/recomendaciones (MS8) | Devuelve eventos publicados o agotados paginados. No exige headers de usuario y no debe exponerse por el API Gateway. |
| GET | `/internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` | Interno: Entradas (MS5) | Consulta la autorización actual sin caché ni headers de usuario; fuera del Gateway. |

## Identidad y usuarios de prueba

`X-User-Id` contiene el identificador del usuario autenticado (el claim `sub` del JWT en el Gateway). `X-User-Roles` contiene los roles `CLIENTE`, `ORGANIZADOR` y/o `ADMIN`, separados por comas. El servicio evalúa la lista completa: por ejemplo, `CLIENTE, ORGANIZADOR` permite operar como organizador cuando también es titular; no existe precedencia entre roles. La comparación no distingue mayúsculas y recorta espacios; no se admite el alias legado `administrador`.

Solo las rutas protegidas requieren identidad. `CLIENTE`, `ORGANIZADOR` y `ADMIN` pueden buscar eventos sin token; el detalle de eventos `publicado` y `agotado` también es público. Para consultar un borrador se requiere `X-User-Id` y que `X-User-Roles` incluya `ORGANIZADOR` (con el mismo ID titular) o `ADMIN`; sin identidad responde `401` y un borrador ajeno o sin rol válido queda oculto con `404`. Solo el organizador titular puede crear y configurar sus eventos; `ADMIN` gestiona lugares y sectores y suspende, reactiva o cancela eventos. Los callbacks y consultas internas no reciben headers de usuario.

Usuarios de ejemplo y sus headers:

| Usuario | Headers |
|---|---|
| Cliente | `X-User-Id: cliente-123`, `X-User-Roles: CLIENTE` |
| Organizador | `X-User-Id: organizador-42`, `X-User-Roles: ORGANIZADOR` |
| Administrador | `X-User-Id: admin-1`, `X-User-Roles: ADMIN` |

El perfil `dev` carga dos lugares y sus sectores: Teatro Central en Buenos Aires (Platea numerada y Pullman no numerado) y Estadio del Parque en Córdoba (Tribuna Norte numerada y Campo de pie no numerado), además de estos eventos:

| Evento | Estado | Lugar | Precios por sector |
|---|---|---|---|
| Festival de Rock Nacional | Publicado | Estadio del Parque (Córdoba) | Tribuna Norte: $20.000; Campo de pie: $8.000 |
| El zorzal | Publicado | Teatro Central (Buenos Aires) | Platea: $12.000; Pullman: $7.000 |
| Final del Torneo | Agotado | Estadio del Parque (Córdoba) | Tribuna Norte: $30.000; Campo de pie: $12.000 |
| Conferencia de Tecnología | Borrador | Teatro Central (Buenos Aires) | Sin precios |
| Recital Cancelado | Cancelado | Estadio del Parque (Córdoba) | Tribuna Norte: $20.000; Campo de pie: $8.000 |

Estos eventos se insertan directamente en la base de este servicio, sin pasar por Reservas. Por eso, el detalle de los eventos publicados muestra `disponibilidad: null` y suspender o cancelar esos eventos responde `503`, porque Reservas no los conoce. La cancelación puede dejar una intención y un trabajo `ERROR_FINAL` si Reservas responde `404`. Para probar el flujo completo, creá y publicá eventos por la API con Reservas encendido (carpeta 12 de Postman).

Las rutas de gestión de eventos y lugares devuelven errores controlados con `timestamp`, `status`, `error` y `mensaje`; `campos` se incluye como lista de `{campo, mensaje}` cuando corresponde.

## Búsqueda y detalle de eventos

`GET /eventos` combina los filtros provistos con AND. `tipo`, `organizadorId`, `participante` y `ciudad` son cadenas exactas. `fechaDesde` y `fechaHasta` son instantes ISO-8601 UTC terminados en `Z`, inclusivos; fechas inválidas o `fechaDesde` posterior a `fechaHasta` producen `400`. Los criterios omitidos no filtran.

| Query param | Tipo | Default y límites |
|---|---|---|
| `tipo` | texto | Sin default; igualdad exacta. |
| `organizadorId` | texto | Sin default; igualdad exacta. |
| `participante` | texto | Sin default; coincidencia exacta en la lista. |
| `ciudad` | texto | Sin default; igualdad exacta. |
| `fechaDesde` | ISO-8601 UTC (`Z`) | Sin default; inclusiva. |
| `fechaHasta` | ISO-8601 UTC (`Z`) | Sin default; inclusiva. |
| `orden` | texto | `fecha`; valores: `fecha`, `precio`, `relevancia`. |
| `direccion` | texto | `asc`; valores: `asc`, `desc`; solo ordena el precio. |
| `q` | texto | Sin default; texto libre para relevancia en nombre, descripción y participantes. |
| `page` | entero | `0` o mayor. |
| `size` | entero | `20`; rango de `1` a `100`. |

`GET /eventos` es público y no requiere token ni headers de identidad. Solo lista eventos `publicado` y `agotado`; borradores, cancelados y finalizados no aparecen. Sin coincidencias la respuesta es `200` con contenido vacío. El orden por fecha es ascendente y por precio usa `precioMinimo` en la dirección solicitada (con fecha e ID como desempate). Con `orden=relevancia` y `q`, se ordena por puntaje de texto descendente y luego fecha ascendente; sin `q`, equivale a fecha ascendente. La respuesta usa `contenido`, `pagina`, `tamano`, `totalElementos` y `totalPaginas`.

Ejemplo de respuesta paginada:

```json
{"contenido":[{"id":"672a1f77bcf86cd799439011","nombre":"Festival Central","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","ciudad":"Buenos Aires","estado":"publicado","ventaSuspendida":false,"precioMinimo":15000.00}],"pagina":0,"tamano":20,"totalElementos":1,"totalPaginas":1}
```

`GET /eventos/{eventoId}` no requiere token para eventos `publicado` o `agotado`; los roles desconocidos se ignoran como en una solicitud anónima. Un borrador solo es visible para el `ORGANIZADOR` titular (mismo `X-User-Id`) o `ADMIN`; sin identidad responde `401` y para otros roles/usuarios responde `404`. Los eventos `cancelado` y `finalizado` también responden `200` y no consultan disponibilidad. En eventos publicados o agotados cada sector incluye `disponibilidad` con `capacidadTotal`, `cantidadDisponible` y, si es numerado, `asientosDisponibles` con pares `{fila, numero}`. Si Reservas falla o no devuelve un sector, ese sector lleva `disponibilidad: null`.

La búsqueda usa una caché local con TTL de `30s` y máximo de `1000` entradas por defecto. El detalle, la disponibilidad y las consultas internas no se cachean. Las escrituras que afectan el catálogo invalidan la caché de la instancia que las procesa; las demás instancias pueden servir datos antiguos hasta que venza su TTL. Una búsqueda concurrente también puede volver a guardar un resultado anterior tras una invalidación, hasta que venza el TTL desde su almacenamiento.

Ejemplo de detalle con ambos tipos de sector y disponibilidad recibida:

```json
{"id":"672a1f77bcf86cd799439011","nombre":"Festival Central","descripcion":"Música en vivo","tipo":"MUSICA","organizadorId":"organizador-42","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"publicado","ventaSuspendida":false,"lugar":{"id":"507f1f77bcf86cd799439011","nombre":"Estadio Central","direccion":"Av. Central 100","ciudad":"Buenos Aires"},"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":1,"asientosPorFila":2,"capacidad":null,"precio":15000.00,"disponibilidad":{"capacidadTotal":2,"cantidadDisponible":1,"asientosDisponibles":[{"fila":1,"numero":2}]}},{"sectorId":"7d444840-9dc0-11d1-b245-5ffdce74fad2","nombre":"Campo","tipo":"NO_NUMERADO","filas":null,"asientosPorFila":null,"capacidad":500,"precio":8000.00,"disponibilidad":{"capacidadTotal":500,"cantidadDisponible":120}}]}
```

## Integración con otros servicios

La comunicación entre servicios es directa, por REST/JSON. Las llamadas salientes de outbox incluyen `X-Service-Token`. Para las rutas protegidas, el Gateway valida el JWT y propaga los headers de identidad; las consultas públicas no los requieren y los endpoints internos se invocan directamente entre servicios.

### Trazabilidad

MS1 acepta `X-Correlation-Id` de hasta 100 caracteres ASCII (letras, números, `-`, `_` y `.`); si falta o es inválido genera un UUID. Lo devuelve en la respuesta y lo incluye en los logs de stdout. Por cada solicitud, salvo `/actuator/**`, registra método, ruta sin query, usuario si existe, estado y duración; no registra tokens ni cuerpos.

El ID se propaga a Reservas, MS6 y las llamadas de outbox. Los avisos de publicación y cancelación lo guardan para conservarlo en los reintentos; los avisos antiguos sin ID generan uno al despacharse. Las llamadas programadas sin contexto también generan uno. La recolección de logs corresponde al Compose general.

El documento [docs/api-gateway-contract.md](docs/api-gateway-contract.md) detalla los paths, headers y reglas de reenvío para el API Gateway.

### Reservas — contrato confirmado

URL base: propiedad `reservas.base-url`, predeterminada en Docker en `http://reservas:8080` (en ejecución desde el host, `http://localhost:8080`). Timeouts: `reservas.connect-timeout` (2 s) y `reservas.read-timeout` (5 s).

Este servicio hace estas llamadas a Reservas:

1. **Inicializar disponibilidad al publicar:** `PUT /disponibilidad/eventos/{eventoId}`. Envía `limiteEntradasPorCliente` y los sectores; para sectores numerados deriva los pares `{fila, numero}` y su `capacidadTotal` a partir de filas × asientosPorFila. La respuesta esperada es `204`; repetir la inicialización con los mismos datos es idempotente.
2. **Cambiar el estado de venta:** `PUT /disponibilidad/eventos/{eventoId}/venta`, con `{"estado":"ACTIVA"}`, `SUSPENDIDA`, `CANCELADA` o `FINALIZADA`. La respuesta esperada es `204`.
3. **Consultar disponibilidad en el detalle:** al consultar un evento `publicado` o `agotado`, este servicio llama `GET /disponibilidad/eventos/{eventoId}` y combina la respuesta por `sectorId`. Si Reservas no responde o devuelve error, el detalle se devuelve igual y `disponibilidad` queda en `null`. Para otros estados no se consulta a Reservas.

Reservas llama a este servicio mediante el callback `PUT /eventos/{eventoId}/disponibilidad`, con `{"agotado":true}` o `{"agotado":false}`. Responde `204` para cambios, avisos duplicados y avisos tardíos de eventos cancelados o finalizados; `404` si el evento no existe y `409` si sigue en borrador. El callback es interno, sin headers de usuario y fuera del Gateway.

Si Reservas falla, no responde o no confirma con `204`, publicar responde `503` y el evento queda en borrador. Si falla al suspender o reactivar, MS1 responde `503` sin cambiar el evento; si Reservas responde `409`, MS1 devuelve `409` sin modificarlo. Cancelar tiene recuperación durable y un `503` no implica ausencia de efectos, como se detalla a continuación.

El equipo de Reservas confirmó que repetir `CANCELADA` sobre una venta cancelada responde `204` y que `CANCELADA` es terminal: intentar pasar a `ACTIVA` o `SUSPENDIDA` responde `409`. Las guardas de MS1 no detienen llamadas remotas que ya estaban en curso al registrar la intención.

### Cancelación recuperable

`PUT /eventos/{eventoId}/cancelar` requiere `ADMIN` y acepta un body opcional `{"motivo":"..."}`. Para un evento publicado o agotado:

1. Una transacción MongoDB registra la intención interna y el trabajo `cancelacion-reservas-{eventoId}`; el evento conserva su estado visible. La primera intención fija el motivo y la correlación.
2. Fuera de la transacción, el dispatcher intenta `PUT /disponibilidad/eventos/{eventoId}/venta` con `{"estado":"CANCELADA"}`. Solo `204` confirma el cierre; otro `2xx` se reintenta.
3. Tras el `204`, una transacción condicionada al reclamo vigente cancela el evento, guarda `canceladoEn` y el motivo, limpia la intención, entrega el trabajo y crea `cancelacion-{eventoId}` para Compras y `cancelacion-fila-{eventoId}` para MS7. La caché del catálogo se invalida después del commit.
4. Los avisos se intentan por separado después del commit y, si corresponde, se recuperan por el scheduler. Una falla de Compras o MS7 no revierte la cancelación ni impide entregar el otro aviso. La aceptación de Compras confirma recepción, no que terminaron los reintegros.

| Código | Resultado |
|---|---|
| `200` | Reservas confirmó el cierre y la transacción local confirmó la cancelación; los avisos pueden seguir pendientes. |
| `503` | No se confirmó la cancelación en este intento o ya hay una intención pendiente. **No implica ausencia de efectos**: si la intención quedó persistida, el scheduler la recupera según la política de outbox. Un `ERROR_FINAL` requiere intervención y sin token el dispatcher permanece pausado. |
| `409` | Evento ya cancelado o finalizado, u otro estado incompatible. |
| `401` / `403` | Falta identidad / la lista de roles no incluye `ADMIN`. |
| `404` | Evento inexistente. |

Repetir mientras la intención está pendiente devuelve `503`, conserva el primer motivo y no crea otro trabajo; una vez cancelado devuelve `409` sin duplicar avisos. Mientras haya intención pendiente, suspender y reactivar responden `409` y la finalización programada omite el evento. Ante `503`, consultá la outbox antes de asumir que no se realizará la cancelación.

### Compras — contrato confirmado

URL base configurable con `compras.base-url` (default Docker `http://compras:8083`; desde el host, `http://localhost:8083`). Compras consulta este servicio directamente por el nombre `lugares:8081`, sin pasar por el Gateway.

- **Consulta interna de evento:** Compras llama directamente `GET /internal/eventos/{eventoId}` sin headers de usuario. Devuelve eventoId, nombre, `fechaInicio` en ISO-8601 UTC, estado en minúsculas y sectores con precio decimal o `null` si todavía no se definió. Responde `200` o `404`.
- **Aviso de cancelación:** después de que Reservas confirma la cancelación, este servicio guarda el evento y el aviso en una transacción MongoDB y llama `POST /eventos-cancelados` después del commit. Envía el ID estable en `X-Aviso-Id` sin modificar el payload. Cualquier respuesta `2xx` marca el aviso como `ENTREGADO`; `3xx` y el resto de `4xx` son definitivos; timeouts, errores de conexión, `5xx`, `408` y `429` se reintentan. El dispatcher usa timeout fijo de 2 s para conexión y 5 s para lectura. El fallo de Compras no revierte la cancelación ni cambia la respuesta `200` al administrador.

MS7 también recibe siempre `PUT {FILAS_BASE_URL}/internal/filas/eventos/{eventoId}/cancelacion` sin body, incluso si `requiereFila=false`. Se envía el aviso `cancelacion-fila-{eventoId}` con los headers comunes de outbox. Cualquier `2xx` confirma su entrega; una falla no afecta al aviso de Compras.

### Política de fila virtual

`GET /internal/eventos/{eventoId}` devuelve `eventoId`, `nombre`, `tipo`, `fechaHora`, `ciudad`, `estado`, `organizadorId`, `requiereFila` y `sectores` con `sectorId`, `nombre`, `tipo`, `filas`, `asientosPorFila`, `capacidad` y `precio`. Conserva `fechaInicio` para Compras, con el mismo instante UTC que `fechaHora`; las dimensiones no aplicables y los precios no definidos son `null`. El GET lee el valor actual sin caché y responde `404` para eventos inexistentes.

MS7 llama `PUT /internal/eventos/{eventoId}/politica-fila` con `{"requiereFila":true}` o `{"requiereFila":false}`. Responde `200` con `eventoId` y el indicador; repetir el mismo valor no escribe ni cambia la versión. Para eventos cancelados, activar responde `409` incluso si ya estaba activo; desactivar está permitido. Un conflicto de versión concurrente también responde `409`. Si el evento no existe responde `404`, antes de validar el body; si existe y el body es inválido, `400`. Los eventos nuevos y los documentos antiguos sin el campo se leen con `false`, sin migración. La pausa la maneja MS7 sin cambiar este indicador; sin fila se mantiene el flujo habitual de reservas.

Ambas rutas son internas, sin headers de usuario y fuera del Gateway.

### Preferencias y recomendaciones (MS8)

- **Consulta interna de eventos publicados:** MS8 llama `GET /internal/eventos-publicados` sin headers de usuario. La ruta devuelve eventos `publicado` o `agotado`, ordenados por fecha e ID, con `page` (default `0`) y `size` (default `100`, máximo `200`). No se publica por el API Gateway.
- **Aviso de publicación:** después de que Reservas confirma la inicialización, MS1 guarda el evento publicado y un aviso `PUBLICACION` en la misma transacción MongoDB. El aviso usa ID `publicacion-{eventoId}`, destino `POST {PREFERENCIAS_BASE_URL}/internal/preferencias/eventos-publicados` y payload `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` y `fechaHora`.

### Personal de acceso

El `ORGANIZADOR` titular o `ADMIN` puede asignar con `POST /eventos/{eventoId}/personal-acceso` y `{"correo":"personal@example.com"}`, listar con `GET` y revocar con `DELETE /eventos/{eventoId}/personal-acceso/{usuarioId}`. El alta consulta a MS6, exige `activo=true` y el rol `PERSONAL_ACCESO`, y devuelve `201`; una asignación activa duplicada responde `409` y una revocada se reactiva. Correo inexistente responde `404`, usuario inactivo o sin rol responde `400`, y fallo o error de autenticación con MS6 responde `503`, sin guardar. Revocar es idempotente y conserva el registro. Por simplicidad se guardan solo el estado y las fechas de la última activación y revocación, sin historial completo.

MS5 consulta `GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` en cada validación: devuelve `200` con `autorizado: false` incluso si el evento o la asignación no existen. Lee el estado actual sin caché, no exige headers de usuario y queda fuera del Gateway.

MS1 consulta `GET {USUARIOS_BASE_URL}/internal/usuarios/por-correo?correo=...` con `X-Service-Token` y `X-Correlation-Id`, con timeouts de conexión de 2 s y lectura de 5 s. Fuera de Docker la URL por defecto es `http://localhost:8087`; en Docker debe configurarse `USUARIOS_BASE_URL` con una dirección alcanzable desde el contenedor, ya que el nombre de servicio aún no está definido. Sin URL o token el alta responde `503`.

La carpeta 21 de Postman requiere MS6 disponible, un evento existente y un usuario activo con rol `PERSONAL_ACCESO`. Configurá `eventoPersonalId`, `organizadorPersonalId` y `correoPersonal`; la primera request guarda `usuarioPersonalId` para las siguientes.

### Outbox de avisos

Los avisos se guardan en la colección MongoDB `outbox`:

| Estado | Significado |
|---|---|
| `PENDIENTE` | Espera el próximo intento. |
| `PROCESANDO` | Reclamado por una instancia; el campo `leaseHasta` marca el vencimiento del lease de 60 s. |
| `ENTREGADO` | El destino confirmó la entrega: `204` para el trabajo de Reservas; cualquier `2xx` para los avisos. |
| `ERROR_FINAL` | Error definitivo o se agotaron los intentos; requiere revisión. |

`OUTBOX_DISPATCHER_INTERVAL` (`outbox.dispatcher.interval`, default `60s`) configura el intervalo del scheduler. El resto de la política actualmente no es configurable: máximo 8 intentos contados al reclamar; espera inicial de 30 s, factor 2, tope de 15 min, sin jitter y `leaseHasta` fijado a 60 s desde el reclamo.

Todos los avisos salientes incluyen `X-Service-Token`, `X-Aviso-Id` y `X-Correlation-Id`. Sin `SERVICIO_TOKEN` en perfiles distintos de `docker`, se registra un aviso al arrancar y el dispatcher no procesa ni reclama avisos: permanecen pendientes sin consumir intentos ni perderse. Configurá el token y reiniciá MS1 para reanudar la entrega. `408`, `429`, `5xx` y errores de transporte se reintentan; `3xx` y los demás `4xx`, incluido `403`, son definitivos.

La entrega inmediata de `PUBLICACION` es síncrona después del commit; si Preferencias no responde, puede demorar hasta unos 7 s (timeouts de conexión y lectura). Ese fallo no revierte la publicación ni cambia su respuesta exitosa. Los reintentos posteriores son asíncronos, a cargo del dispatcher.

Para inspeccionar avisos con `mongosh`:

```javascript
use ticketya_lugares
db.outbox.find({ estado: "PENDIENTE" }).sort({ proximoIntento: 1 })
db.outbox.find({ estado: "PROCESANDO" }).sort({ leaseHasta: 1 })
db.outbox.find({ estado: "ERROR_FINAL" }).sort({ actualizadoEn: -1 })
db.outbox.find({ _id: "cancelacion-reservas-<eventoId>" })
db.outbox.find({ _id: "cancelacion-<eventoId>" })
db.outbox.find({ _id: "cancelacion-fila-<eventoId>" })
db.outbox.find({ _id: "publicacion-<eventoId>" })
```

`ERROR_FINAL` no se reintenta solo. Investigá `ultimoError`, el estado del evento y del servicio remoto, corregí la causa y verificá que sea seguro reenviar con el mismo ID. Para reencolar **solo** el aviso revisado, conectate a la base correcta con `mongosh` y reemplazá el ID:

```javascript
db.outbox.updateOne(
  { _id: "cancelacion-reservas-<eventoId>", estado: "ERROR_FINAL" },
  { $set: { estado: "PENDIENTE", intentos: 0, proximoIntento: new Date(), actualizadoEn: new Date() },
    $unset: { leaseHasta: "", ultimoError: "" } }
)
```

Para Compras o MS7 usá su ID respectivo. La condición evita tocar reclamos activos; conservá destino, payload, motivo y correlación. El scheduler volverá a reclamar el trabajo elegible si hay token configurado.

Al arrancar, un backfill transitorio busca documentos antiguos con `avisoCancelacionPendiente=true`. Inserta avisos `PENDIENTE` con ID determinístico `cancelacion-{eventoId}`; si el ID ya existe no sobrescribe el aviso. Solo después de que el aviso exista elimina el campo legacy. Registra los conteos de migrados, ya existentes y omitidos; eventos legacy inconsistentes se omiten con `WARN` y no bloquean el arranque.

El GET interno es una ruta directa entre servicios: no exige headers de usuario y no se publica por el Gateway. Los errores remotos del aviso no revierten la cancelación.

## Puertos de servicios vecinos

MS1 escucha en el puerto interno `8081` por defecto (`PORT` permite sobrescribirlo). Los servicios confirmados son Reservas `reservas:8080`, Compras `compras:8083`, Fila virtual `fila-virtual:8088`, Gateway `gateway:8086`, Pagos `pagos:8084` y Entradas `entradas:8085`. Reservas y Compras llaman a MS1 directamente por nombre de servicio, no mediante el Gateway; el Gateway usa `MS1_URL=http://lugares:8081` y no enruta `/actuator` ni `/internal`. MongoDB no está conectado a `ticketya-net`.

## Variables de entorno

Defaults aplicados por el Compose de Docker:

| Variable de entorno | Propiedad | Default en Docker |
|---|---|---|
| `PORT` | `server.port` | `8081` |
| `MONGODB_URI` | `spring.data.mongodb.uri` | `mongodb://mongodb:27017/ticketya_lugares?replicaSet=rs0` |
| `RESERVAS_BASE_URL` | `reservas.base-url` | `http://reservas:8080` |
| `RESERVAS_CONNECT_TIMEOUT` | `reservas.connect-timeout` | `2s` |
| `RESERVAS_READ_TIMEOUT` | `reservas.read-timeout` | `5s` |
| `COMPRAS_BASE_URL` | `compras.base-url` | `http://compras:8083` |
| `FILAS_BASE_URL` | `filas.base-url` | `http://fila-virtual:8088` |
| `USUARIOS_BASE_URL` | `usuarios.base-url` | Sin default; configurar para asignar personal |
| `PREFERENCIAS_BASE_URL` | `preferencias.base-url` | `http://preferencias:8089` |
| `SERVICIO_TOKEN` | `servicio.token` | Sin default; obligatorio con perfil `docker` |
| `CATALOGO_CACHE_TTL` | `catalogo.cache.ttl` | `30s` |
| `CATALOGO_CACHE_MAXIMO_ENTRADAS` | `catalogo.cache.maximo-entradas` | `1000` |
| `EVENTOS_FINALIZACION_INTERVAL` | `eventos.finalizacion.interval` | `60s` |
| `OUTBOX_DISPATCHER_INTERVAL` | `outbox.dispatcher.interval` | `60s` |
| `SPRING_PROFILES_ACTIVE` | Perfil activo de Spring | `docker` |

El default de host de `MONGODB_URI` en `application.yml` es `mongodb://localhost:27017/ticketya_lugares?directConnection=true`; usa `directConnection=true` porque el replica set anuncia su miembro con el nombre Docker `mongodb:27017`. Desde el host, las URLs predeterminadas son Reservas `http://localhost:8080`, Compras `http://localhost:8083`, Fila `http://localhost:8088`, Usuarios `http://localhost:8087` y Preferencias `http://localhost:8089`. En Docker, `USUARIOS_BASE_URL` queda vacía hasta configurar una URL alcanzable: el nombre de servicio de MS6 aún no está confirmado. Los timeouts de Reservas tienen defaults `2s`/`5s`; el Compose base no pasa `RESERVAS_CONNECT_TIMEOUT` ni `RESERVAS_READ_TIMEOUT` al contenedor.

El trabajo de Reservas usa sus timeouts configurables; los avisos a otros destinos usan conexión `2s` y lectura `5s` fijos. El máximo de intentos, backoff y lease también son fijos; no hay variables de entorno para esos valores.

`CATALOGO_CACHE_TTL` y `CATALOGO_CACHE_MAXIMO_ENTRADAS` deben ser positivos. Las propiedades tienen los mismos defaults al ejecutar MS1 desde el host.

## Pruebas con Postman

En `postman/` están las dos colecciones:

- `TicketYa - Lugares y sectores.postman_collection.json`: pruebas de lugares y sectores.
- `TicketYa - Eventos.postman_collection.json`: pruebas de eventos, integraciones y búsqueda, con carpetas numeradas de **00 a 22**.

Las carpetas son: 00 Preparación; 01 Crear evento; 02 Límite por cliente al crear; 03 Precio por sector; 04 Modificar límite; 05 Publicar y gestión (validaciones previas); 06 flujo completo con Reservas; 07 fallo al publicar con Reservas apagado; 08 GET internos para Compras y Preferencias; 09 aviso de cancelación con Reservas y Compras; 10 aviso pendiente con Compras apagado; 11 reenvío del aviso; 12 preparación de búsqueda; 13 filtros; 14 orden y relevancia; 15 paginación y validaciones; 16 roles y headers; 17 detalle con Reservas encendido; 18 detalle sin disponibilidad con Reservas apagado; 19 identidad JWT, roles y titularidad.

La carpeta **20 Política de fila** usa `eventoCompraId` preparado por la carpeta 08. Comprueba activación, consulta actual, repetición, desactivación, body inválido y evento inexistente; la versión y los casos de cancelación se verifican en las pruebas automatizadas.

La carpeta **21 Personal de acceso** prueba la gestión implementada. Configurá localmente `eventoPersonalId`, `organizadorPersonalId`, `correoPersonal` y `usuarioPersonalId`. La carpeta **22 Cancelación recuperable** contiene requests para el caso feliz, la repetición, la intención pendiente y los controles de los stubs; ejecutá sus escenarios por separado como se indica más abajo.

Para el flujo de eventos, importá cada colección una vez y ejecutá primero **00 Preparación**. No reimportes la colección entre carpetas: se perderían las variables guardadas durante la corrida. **05 Publicar y gestión: validaciones previas** funciona con Reservas encendido o apagado. **08 GET internos** no depende de servicios externos; la consulta de Preferencias puede devolver contenido vacío si todavía no hay eventos publicados o agotados.

Las carpetas de aviso de cancelación se ejecutan en este orden y con estos servicios:

1. **09 [Reservas y Compras ENCENDIDOS]** requiere ambos servicios respondiendo en `localhost:8080` y `localhost:8083`.
2. **10 [Reservas ENCENDIDO, Compras APAGADO]** requiere Reservas respondiendo en `localhost:8080` y que Compras no esté disponible en `localhost:8083`.
3. **11 [Correr después de la 10]** se ejecuta después de volver a encender Compras en `localhost:8083` y esperar el siguiente ciclo del dispatcher, hasta `outbox.dispatcher.interval` (60 s por defecto).

Las carpetas **09 y 10 son excluyentes**: usan escenarios de cancelación separados. La 11 completa el caso de aviso pendiente creado en la 10.

Para simular Compras, desde `mcsv-lugares-sectores-eventos` ejecutá en otra terminal:

```sh
node postman/compras-eventos-stub.js
```

El stub escucha en `localhost:8083` por defecto (`PORT` permite cambiarlo), imprime y registra los avisos recibidos. Devuelve `202` al aceptar uno nuevo y `200` cuando ya lo procesó. Permite consultar los avisos con `GET /_stub/avisos`. Requiere Node.js. No puede compartir puerto con `compras-stub.js`.

Para simular Preferencias y recomendaciones (MS8), desde `mcsv-lugares-sectores-eventos` ejecutá en otra terminal:

```sh
node postman/preferencias-stub.js
```

Requiere Node.js. Escucha en el puerto `8089` por defecto; `PORT` permite cambiarlo. Si MS1 corre en Docker, configurá `PREFERENCIAS_BASE_URL=http://host.docker.internal:8089` para que el contenedor alcance el stub del host (ajustá el puerto también si cambiaste `PORT`).

Para verificar el token, configurá `SERVICIO_TOKEN` con el mismo valor en el entorno del stub antes de iniciarlo y en MS1 antes de arrancar. El stub responde `403` si el header falta o no coincide; sin token configurado no lo valida. No incluyas el secreto en el repositorio.

El stub recibe `POST /internal/preferencias/eventos-publicados`, imprime el aviso y su header `X-Aviso-Id`, y valida que estén `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` y `fechaHora`; `participantes` debe ser una lista. Devuelve `202` para un `eventoId` nuevo y `200` si ya lo había recibido. `GET /_stub/avisos` lista lo recibido y `DELETE /_stub/avisos` limpia la lista. Para simular la entrega, `POST /_stub/modo/ok` activa respuestas normales, `POST /_stub/modo/error` hace que el aviso responda `503`, y `POST /_stub/modo/timeout` deja el aviso sin respuesta; los endpoints para cambiar el modo responden normalmente.

Para los escenarios anteriores que prueban publicación y fallas de Reservas, ejecutá **06** o **07** por separado:

- **06 [Reservas ENCENDIDO]** necesita Reservas respondiendo en `localhost:8080`.
- **07 [Reservas APAGADO]** requiere que no haya ningún proceso escuchando en el puerto `8080`.

Las carpetas **06 y 07 también son excluyentes**.

Las carpetas **12 a 17 se ejecutan juntas en una sola corrida**, con Reservas encendido y el mismo simulador de Reservas: guarda en memoria los eventos inicializados y los datos que usan esas pruebas. La carpeta **17 Detalle del evento** ocupa lugares (caso DT15); para repetirla hay que volver a ejecutar la 12, con Reservas encendido, para reinicializar disponibilidad.

La carpeta **18 [Reservas APAGADO] Detalle sin disponibilidad** requiere haber corrido la 12 con Reservas encendido y luego apagar Reservas. También se puede reiniciar el simulador sin volver a publicar: en ese caso Reservas responde `404`. El detalle del evento responde `200` y cada sector queda con `disponibilidad: null`.

Para simular Reservas en local, desde la carpeta mcsv-lugares-sectores-eventos ejecutá en otra terminal:

```sh
node postman/reservas-stub.js
```

El stub de Reservas escucha en el puerto `8080` por defecto (`PORT` permite cambiarlo), imprime los pedidos recibidos y valida el formato de las rutas y los cuerpos. También responde `GET /disponibilidad/eventos/{id}`; `POST /_stub/ocupar/{eventoId}` permite simular ocupación y `DELETE /_stub/eventos` limpia los datos del simulador. Requiere Node.js.

El stub de Compras también ofrece `GET /_stub/avisos` para consultar avisos recibidos y `DELETE /_stub/avisos` para limpiarlos.

### Pruebas manuales de cancelación recuperable

En terminales separadas, desde esta carpeta, iniciá los simuladores (solo para uso local):

```sh
node postman/reservas-stub.js
node postman/compras-eventos-stub.js
node postman/fila-stub.js
node postman/usuarios-stub.js
```

Escuchan por defecto en `8080`, `8083`, `8088` y `8087`, respectivamente. Configurá un `SERVICIO_TOKEN` no vacío en MS1; si también lo configurás en los stubs de Compras, Fila o Usuarios, debe coincidir. Si MS1 corre en Docker, configurá las cuatro URLs correspondientes como `http://host.docker.internal:<puerto>` en `.env` y recreá MS1; desde Postman en el host, las URLs de los stubs usan `localhost`. No publiques ni compartas el token.

Importá la colección y prepará **dos eventos distintos publicados** con Reservas en modo `ok`: podés ejecutar los pasos de creación, precios y publicación de las carpetas 09 y 10 sin ejecutar todavía sus cancelaciones. Copiá sus IDs en `eventoCancelacionOkId` y `eventoCancelacionPendienteId` de la colección. No reinicies el stub de Reservas entre publicar y cancelar: sus datos están solo en memoria. Ejecutá los requests de la carpeta 22 individualmente, no toda la carpeta en orden: sus modos son alternativos.

1. **Feliz y repetida:** poné Reservas, Compras y Fila en `ok`, cancelá el primer evento (`200`) y repetí (`409`). Consultá `GET /_stub/avisos` de Compras y `GET /_stub/cancelaciones` de Fila: deben mostrar los IDs estables, el token como presente y la correlación; el body de Fila es `null`. Consultá también los tres IDs en `outbox`.
2. **Recuperación tras error o timeout:** poné Reservas en `error` (`500`) o `timeout` (sin respuesta), cancelá el segundo evento y comprobá `503`; repetí mientras siga pendiente y comprobá otro `503`. No hay avisos a Compras ni Fila antes del `204` de Reservas. Volvé a `ok` y esperá el próximo intento elegible y una pasada del scheduler: el evento queda cancelado y aparecen ambos avisos. El backoff y el intervalo del scheduler pueden sumar espera.
3. **Respuesta perdida:** con un evento nuevo publicado, poné Reservas en `perder-respuesta` y cancelalo. El stub aplica `CANCELADA` y corta la conexión; MS1 responde `503`. Consultá `GET /_stub/venta/{eventoId}`, cambiá el stub a `ok` y esperá el reintento: Reservas acepta repetir `CANCELADA` con `204` y MS1 completa la cancelación sin duplicar avisos.
4. **Avisos independientes:** con eventos nuevos, probá `error` o `timeout` en Compras y en Fila por separado. MS1 responde `200` tras confirmar la cancelación; el aviso fallido queda pendiente y el otro puede entregarse. En Fila, `invalido` devuelve `400` y produce `ERROR_FINAL`; revisalo y reencolalo solo después de corregir la causa, como se indicó en la sección de outbox.

Los controles locales están en la carpeta 22: Reservas `POST /_stub/modo/{ok|error|timeout|perder-respuesta}` y `GET /_stub/venta/{eventoId}`; Compras `POST /_stub/modo/{ok|error|timeout}` y `GET /_stub/avisos`; Fila `POST /_stub/modo/{ok|error|timeout|invalido}` y `GET /_stub/cancelaciones`; Usuarios `POST /_stub/modo/{ok|error|timeout}` y `GET /_stub/consultas`. Restaurá los modos a `ok` al terminar. El stub de Usuarios permite además probar la carpeta 21 con `personal@ticketya.com`.

## Ejecutar las pruebas automatizadas

Desde `mcsv-lugares-sectores-eventos`:

```sh
mvn clean test
```

Las pruebas de integración de MongoDB usan Testcontainers y requieren Docker iniciado.

## Modelo de datos

El diagrama, los campos, índices, integraciones y decisiones del modelo se describen en [docs/modelo-de-datos.md](docs/modelo-de-datos.md).

## Diseño

Las responsabilidades, integraciones, secuencias, decisiones de diseño y trazabilidad a OpenSpec/pruebas están en [docs/diseno.md](docs/diseno.md).
