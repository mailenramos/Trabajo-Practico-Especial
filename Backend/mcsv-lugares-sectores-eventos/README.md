# Gestión de lugares, sectores y eventos

Microservicio Spring Boot de TicketYa. Gestiona lugares físicos, sus sectores y el ciclo de vida de eventos. Persiste en MongoDB en colecciones propias; la comunicación entre servicios usa IDs y llamadas directas.

## Requisitos

- JDK 21 o superior.
- Maven 3.9 o superior.
- Docker Desktop con Docker Compose para MongoDB y las pruebas de integración.
- Node.js (opcional, para simular Reservas o Compras desde Postman).

## Levantar MongoDB y el servicio

Ejecutá estos pasos desde `mcsv-lugares-sectores-eventos`.

1. Iniciá MongoDB:

   ```sh
   docker compose up -d mongodb
   ```

   MongoDB queda disponible en `localhost:27017`; Compose conserva los datos en `mongodb_data`.

2. Iniciá el servicio:

   ```sh
   mvn spring-boot:run
   ```

   Por defecto, MongoDB usa `mongodb://localhost:27017/ticketya_lugares` y el servicio escucha en `http://localhost:8081`. El perfil predeterminado es `dev` y carga datos de ejemplo.

3. Consultá lugares con headers de identidad:

   ```sh
   curl.exe -H "X-User-Id: organizador-demo" -H "X-User-Role: ORGANIZADOR" http://localhost:8081/lugares
   ```

4. La documentación interactiva está en <http://localhost:8081/swagger-ui/index.html> y el documento OpenAPI en <http://localhost:8081/v3/api-docs>.

Para detener MongoDB sin borrar sus datos:

```sh
docker compose down
```

## Endpoints

El servicio local usa `http://localhost:8081` por defecto. Las rutas públicas requieren `X-User-Id` junto con el rol indicado; los endpoints internos de callback y consulta entre servicios no exigen headers de usuario. El Gateway debe enviar `X-User-Role: CLIENTE`, `ORGANIZADOR` o `ADMIN`. El servicio compara sin distinguir mayúsculas y recorta espacios externos.

| Método | Ruta | Rol requerido | Descripción |
|---|---|---|---|
| POST | `/lugares` | `ADMIN` | Registra un lugar. |
| GET | `/lugares` | `ORGANIZADOR` o `ADMIN` | Lista lugares. |
| GET | `/lugares/{id}` | `ORGANIZADOR` o `ADMIN` | Consulta un lugar y sus sectores. |
| GET | `/lugares/{id}/sectores` | `ORGANIZADOR` o `ADMIN` | Lista los sectores de un lugar. |
| POST | `/lugares/{id}/sectores` | `ADMIN` | Agrega un sector numerado o no numerado. |
| POST | `/eventos` | `ORGANIZADOR` | Crea un evento en borrador. |
| GET | `/eventos` | `CLIENTE`, `ORGANIZADOR` o `ADMIN` | Busca eventos publicados y agotados con `tipo`, `organizadorId`, `participante`, `ciudad`, `fechaDesde`, `fechaHasta`, `orden`, `direccion`, `q`, `page` y `size`. |
| GET | `/eventos/{eventoId}` | `CLIENTE`, `ORGANIZADOR` o `ADMIN` | Devuelve el detalle público; solo el organizador dueño o ADMIN ve un borrador. |
| PUT | `/eventos/{eventoId}/sectores/{sectorId}/precio` | `ORGANIZADOR` dueño | Define el precio de un sector del evento en borrador. |
| PUT | `/eventos/{eventoId}/limite-entradas-por-cliente` | `ORGANIZADOR` dueño | Actualiza el límite por cliente durante el borrador. |
| PUT | `/eventos/{eventoId}/publicar` | `ORGANIZADOR` dueño | Inicializa la disponibilidad con Reservas y publica el evento. |
| PUT | `/eventos/{eventoId}/venta/suspender` | `ADMIN` | Suspende la venta sin crear un estado formal nuevo. |
| PUT | `/eventos/{eventoId}/venta/reactivar` | `ADMIN` | Reactiva la venta. |
| PUT | `/eventos/{eventoId}/cancelar` | `ADMIN` | Cancela el evento después de confirmarlo con Reservas; body opcional `{"motivo":"..."}`. |
| PUT | `/eventos/{eventoId}/disponibilidad` | Interno: Reservas | Recibe el aviso de agotado o recuperación. No exige `X-User-Id` ni `X-User-Role`; no debe exponerse por el API Gateway. |
| GET | `/internal/eventos/{eventoId}` | Interno: Compras | Consulta datos del evento en cualquier estado. No exige headers de usuario y no debe exponerse por el API Gateway. |

## Identidad y usuarios de prueba

Los valores canónicos de `X-User-Role` son `CLIENTE`, `ORGANIZADOR` y `ADMIN`; la comparación no distingue mayúsculas y recorta espacios externos. En las operaciones de gestión de lugares y en las acciones administrativas del ciclo de vida, el código también acepta `administrador` como alias de `ADMIN`. Búsqueda y detalle públicos solo aceptan los valores canónicos. Ejemplos de headers: `X-User-Id: cliente-123` con `X-User-Role: CLIENTE`; `X-User-Id: organizador-42` con `X-User-Role: ORGANIZADOR`; `X-User-Id: admin-1` con `X-User-Role: ADMIN`.

`X-User-Id` debe identificar al usuario. `CLIENTE`, `ORGANIZADOR` y `ADMIN` pueden buscar y consultar detalles de eventos; solo el dueño `ORGANIZADOR` puede crear y configurar sus eventos; `ADMIN` gestiona lugares y sectores y administra suspensiones, reactivaciones y cancelaciones. Los callbacks y consultas internas no reciben headers de usuario.

Usuarios de ejemplo y sus headers:

| Usuario | Headers |
|---|---|
| Cliente | `X-User-Id: cliente-123`, `X-User-Role: CLIENTE` |
| Organizador | `X-User-Id: organizador-42`, `X-User-Role: ORGANIZADOR` |
| Administrador | `X-User-Id: admin-1`, `X-User-Role: ADMIN` |

El perfil `dev` carga dos lugares y sus sectores: Teatro Central en Buenos Aires (Platea numerada y Pullman no numerado) y Estadio del Parque en Córdoba (Tribuna Norte numerada y Campo de pie no numerado), además de estos eventos:

| Evento | Estado | Lugar | Precios por sector |
|---|---|---|---|
| Festival de Rock Nacional | Publicado | Estadio del Parque (Córdoba) | Tribuna Norte: $20.000; Campo de pie: $8.000 |
| El zorzal | Publicado | Teatro Central (Buenos Aires) | Platea: $12.000; Pullman: $7.000 |
| Final del Torneo | Agotado | Estadio del Parque (Córdoba) | Tribuna Norte: $30.000; Campo de pie: $12.000 |
| Conferencia de Tecnología | Borrador | Teatro Central (Buenos Aires) | Sin precios |
| Recital Cancelado | Cancelado | Estadio del Parque (Córdoba) | Tribuna Norte: $20.000; Campo de pie: $8.000 |

Estos eventos se insertan directamente en la base de este servicio, sin pasar por Reservas. Por eso, el detalle de los eventos publicados muestra `disponibilidad: null` y suspender o cancelar esos eventos responde `503`, porque Reservas no los conoce. Para probar el flujo completo, creá y publicá eventos por la API con Reservas encendido (carpeta 12 de Postman).

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

Solo se listan eventos `publicado` y `agotado`; sin coincidencias la respuesta es `200` con contenido vacío. El orden por fecha es ascendente y por precio usa `precioMinimo` en la dirección solicitada (con fecha e ID como desempate). Con `orden=relevancia` y `q`, se ordena por puntaje de texto descendente y luego fecha ascendente; sin `q`, equivale a fecha ascendente. La respuesta usa `contenido`, `pagina`, `tamano`, `totalElementos` y `totalPaginas`.

Ejemplo de respuesta paginada:

```json
{"contenido":[{"id":"672a1f77bcf86cd799439011","nombre":"Festival Central","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","ciudad":"Buenos Aires","estado":"publicado","ventaSuspendida":false,"precioMinimo":15000.00}],"pagina":0,"tamano":20,"totalElementos":1,"totalPaginas":1}
```

En `GET /eventos/{eventoId}`, un borrador solo es visible para el `ORGANIZADOR` dueño (mismo `X-User-Id`) y `ADMIN`; para otros roles responde `404`. Los demás estados se pueden consultar con cualquiera de los tres roles. En eventos publicados o agotados cada sector incluye `disponibilidad` con `capacidadTotal`, `cantidadDisponible` y, si es numerado, `asientosDisponibles` con pares `{fila, numero}`. Si Reservas falla o no devuelve un sector, ese sector lleva `disponibilidad: null`.

Ejemplo de detalle con ambos tipos de sector y disponibilidad recibida:

```json
{"id":"672a1f77bcf86cd799439011","nombre":"Festival Central","descripcion":"Música en vivo","tipo":"MUSICA","organizadorId":"organizador-42","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"publicado","ventaSuspendida":false,"lugar":{"id":"507f1f77bcf86cd799439011","nombre":"Estadio Central","direccion":"Av. Central 100","ciudad":"Buenos Aires"},"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":1,"asientosPorFila":2,"capacidad":null,"precio":15000.00,"disponibilidad":{"capacidadTotal":2,"cantidadDisponible":1,"asientosDisponibles":[{"fila":1,"numero":2}]}},{"sectorId":"7d444840-9dc0-11d1-b245-5ffdce74fad2","nombre":"Campo","tipo":"NO_NUMERADO","filas":null,"asientosPorFila":null,"capacidad":500,"precio":8000.00,"disponibilidad":{"capacidadTotal":500,"cantidadDisponible":120}}]}
```

## Integración con otros servicios

La comunicación entre servicios en la Entrega 1 es directa, por REST/JSON, y no usa autenticación entre servicios. El Gateway propaga los headers de usuario solo para las rutas públicas.

El documento [docs/api-gateway-contract.md](docs/api-gateway-contract.md) detalla los paths, headers y reglas de reenvío para el API Gateway.

### Reservas — contrato confirmado

URL base: propiedad `reservas.base-url`, predeterminada en `http://localhost:8080`. Timeouts: `reservas.connect-timeout` (2 s) y `reservas.read-timeout` (5 s).

Este servicio hace estas llamadas a Reservas:

1. **Inicializar disponibilidad al publicar:** `PUT /disponibilidad/eventos/{eventoId}`. Envía `limiteEntradasPorCliente` y los sectores; para sectores numerados deriva los pares `{fila, numero}` y su `capacidadTotal` a partir de filas × asientosPorFila. La respuesta esperada es `204`; repetir la inicialización con los mismos datos es idempotente.
2. **Cambiar el estado de venta:** `PUT /disponibilidad/eventos/{eventoId}/venta`, con `{"estado":"ACTIVA"}`, `SUSPENDIDA`, `CANCELADA` o `FINALIZADA`. La respuesta esperada es `204`.
3. **Consultar disponibilidad en el detalle:** al consultar un evento `publicado` o `agotado`, este servicio llama `GET /disponibilidad/eventos/{eventoId}` y combina la respuesta por `sectorId`. Si Reservas no responde o devuelve error, el detalle se devuelve igual y `disponibilidad` queda en `null`. Para otros estados no se consulta a Reservas.

Reservas llama a este servicio mediante el callback `PUT /eventos/{eventoId}/disponibilidad`, con `{"agotado":true}` o `{"agotado":false}`. Responde `204` para cambios, avisos duplicados y avisos tardíos de eventos cancelados o finalizados; `404` si el evento no existe y `409` si sigue en borrador. El callback es interno, sin headers de usuario y fuera del Gateway.

Si Reservas falla, no responde o no confirma con `204`, publicar responde `503` y el evento queda en borrador. Si falla al suspender, reactivar o cancelar, el servicio responde `503` y no cambia el estado local; la operación se puede reintentar.

### Compras — contrato confirmado

Contrato confirmado con Compras y Devoluciones. URL base configurable con `compras.base-url` (default `http://localhost:8083`); timeouts: `compras.connect-timeout` (2 s) y `compras.read-timeout` (5 s).

- **Consulta interna de evento:** Compras llama directamente `GET /internal/eventos/{eventoId}` sin headers de usuario. Devuelve eventoId, nombre, `fechaInicio` en ISO-8601 UTC, estado en minúsculas y sectores con precio decimal o `null` si todavía no se definió. Responde `200` o `404`.
- **Aviso de cancelación:** este servicio llama `POST /eventos-cancelados` después de que Reservas confirma la cancelación local. El aviso se considera entregado solo si Compras responde `200` o `202`; cualquier otro resultado, incluidos `4xx`, `5xx` y timeout, lo deja pendiente. El scheduler reintenta cada `eventos.cancelacion.reenvio-interval` (60 s por defecto), sin backoff ni límite de intentos. El aviso pendiente se guarda en `avisoCancelacionPendiente`, dentro del documento del evento, y sobrevive a un reinicio. Una vez entregado, no se vuelve a enviar. Con varias instancias puede enviarse duplicado; Compras absorbe el duplicado y responde `200`.

El GET interno es una ruta directa entre servicios: no exige `X-User-Id` ni `X-User-Role` y no se publica por el Gateway. Los errores remotos del aviso no revierten la cancelación.

## Puertos

Este servicio usa el puerto `8081` por defecto (`PORT` permite sobrescribirlo). Reservas usa `8080` y Compras `8083` en el entorno local.

## Variables de entorno y comandos

`application.yml` y las clases de propiedades de los clientes definen estos nombres y defaults:

| Variable de entorno | Propiedad | Default |
|---|---|---|
| `PORT` | `server.port` | `8081` |
| `MONGODB_URI` | `spring.data.mongodb.uri` | `mongodb://localhost:27017/ticketya_lugares` |
| `RESERVAS_BASE_URL` | `reservas.base-url` | `http://localhost:8080` |
| `RESERVAS_CONNECT_TIMEOUT` | `reservas.connect-timeout` | `2s` |
| `RESERVAS_READ_TIMEOUT` | `reservas.read-timeout` | `5s` |
| `COMPRAS_BASE_URL` | `compras.base-url` | `http://localhost:8083` |
| `COMPRAS_CONNECT_TIMEOUT` | `compras.connect-timeout` | `2s` |
| `COMPRAS_READ_TIMEOUT` | `compras.read-timeout` | `5s` |
| `EVENTOS_FINALIZACION_INTERVAL` | `eventos.finalizacion.interval` | `60s` |
| `EVENTOS_CANCELACION_REENVIO_INTERVAL` | `eventos.cancelacion.reenvio-interval` | `60s` |
| `SPRING_PROFILES_ACTIVE` | Perfil activo de Spring | `dev` (perfil predeterminado) |

Ejemplos para configurar variables e iniciar el servicio desde la carpeta `mcsv-lugares-sectores-eventos`:

**PowerShell**

```powershell
$env:PORT = "8081"
$env:MONGODB_URI = "mongodb://localhost:27017/ticketya_lugares"
$env:RESERVAS_BASE_URL = "http://localhost:8080"
$env:RESERVAS_CONNECT_TIMEOUT = "2s"
$env:RESERVAS_READ_TIMEOUT = "5s"
$env:COMPRAS_BASE_URL = "http://localhost:8083"
$env:COMPRAS_CONNECT_TIMEOUT = "2s"
$env:COMPRAS_READ_TIMEOUT = "5s"
$env:EVENTOS_FINALIZACION_INTERVAL = "60s"
$env:EVENTOS_CANCELACION_REENVIO_INTERVAL = "60s"
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

**cmd**

```bat
set PORT=8081
set MONGODB_URI=mongodb://localhost:27017/ticketya_lugares
set RESERVAS_BASE_URL=http://localhost:8080
set RESERVAS_CONNECT_TIMEOUT=2s
set RESERVAS_READ_TIMEOUT=5s
set COMPRAS_BASE_URL=http://localhost:8083
set COMPRAS_CONNECT_TIMEOUT=2s
set COMPRAS_READ_TIMEOUT=5s
set EVENTOS_FINALIZACION_INTERVAL=60s
set EVENTOS_CANCELACION_REENVIO_INTERVAL=60s
set SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

**Git Bash**

```bash
export PORT=8081
export MONGODB_URI=mongodb://localhost:27017/ticketya_lugares
export RESERVAS_BASE_URL=http://localhost:8080
export RESERVAS_CONNECT_TIMEOUT=2s
export RESERVAS_READ_TIMEOUT=5s
export COMPRAS_BASE_URL=http://localhost:8083
export COMPRAS_CONNECT_TIMEOUT=2s
export COMPRAS_READ_TIMEOUT=5s
export EVENTOS_FINALIZACION_INTERVAL=60s
export EVENTOS_CANCELACION_REENVIO_INTERVAL=60s
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

## Pruebas con Postman

En `postman/` están las dos colecciones:

- `TicketYa-Lugares.postman_collection.json`: pruebas de lugares y sectores.
- `TicketYa-Eventos.postman_collection.json`: pruebas de eventos, integraciones y búsqueda, con carpetas numeradas de **00 a 18**.

Las carpetas son: 00 Preparación; 01 Crear evento; 02 Límite por cliente al crear; 03 Precio por sector; 04 Modificar límite; 05 Publicar y gestión (validaciones previas); 06 flujo completo con Reservas; 07 fallo al publicar con Reservas apagado; 08 GET interno para Compras; 09 aviso de cancelación con Reservas y Compras; 10 aviso pendiente con Compras apagado; 11 reenvío del aviso; 12 preparación de búsqueda; 13 filtros; 14 orden y relevancia; 15 paginación y validaciones; 16 roles y headers; 17 detalle con Reservas encendido; 18 detalle sin disponibilidad con Reservas apagado.

Para el flujo de eventos, importá cada colección una vez y ejecutá primero **00 Preparación**. No reimportes la colección entre carpetas: se perderían las variables guardadas durante la corrida. **05 Publicar y gestión: validaciones previas** funciona con Reservas encendido o apagado. **08 GET interno para Compras** no depende de servicios externos.

Las carpetas de aviso de cancelación se ejecutan en este orden y con estos servicios:

1. **09 [Reservas y Compras ENCENDIDOS]** requiere ambos servicios respondiendo en `localhost:8080` y `localhost:8083`.
2. **10 [Reservas ENCENDIDO, Compras APAGADO]** requiere Reservas respondiendo en `localhost:8080` y que Compras no esté disponible en `localhost:8083`.
3. **11 [Correr después de la 10]** se ejecuta después de volver a encender Compras en `localhost:8083` y esperar el siguiente ciclo de reenvío, hasta `eventos.cancelacion.reenvio-interval` (60 s por defecto).

Las carpetas **09 y 10 son excluyentes**: usan escenarios de cancelación separados. La 11 completa el caso de aviso pendiente creado en la 10.

Para simular Compras, desde `mcsv-lugares-sectores-eventos` ejecutá en otra terminal:

```sh
node postman/compras-stub.js
```

El stub escucha en `localhost:8083` por defecto (`PORT` permite cambiarlo), imprime y registra los avisos recibidos. Devuelve `202` al aceptar uno nuevo y `200` cuando ya lo procesó. Permite consultar los avisos con `GET /_stub/avisos`. Requiere Node.js.

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
