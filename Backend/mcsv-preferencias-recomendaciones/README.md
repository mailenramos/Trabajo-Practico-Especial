# Gestión de preferencias (MS8)

Microservicio Spring Boot 3.4.4 y Java 21 para gestionar preferencias, recomendar eventos y avisar sobre publicaciones de interés. Persiste las preferencias en su propia base MongoDB standalone `ticketya_preferencias`.

## Requisitos

- JDK 21 y Maven 3.9 o superior para ejecutar en el host (también se puede usar el Maven Wrapper).
- Docker Desktop con Docker Compose para ejecutar MongoDB, el servicio o las pruebas con Testcontainers.
- Red Docker externa `ticketya-net` para que MS8 se integre con el Gateway y otros servicios.

## Ejecutar en Docker

Crear la red externa compartida una vez por entorno Docker:

```sh
docker network create ticketya-net
```

Desde esta carpeta, iniciar MS8 y MongoDB. El Compose base no publica puertos; MongoDB queda solo en la red privada del proyecto y no se une a `ticketya-net`.

```sh
docker compose up -d --build
```

Para desarrollo local, combinar el Compose base con el override:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

El override publica MS8 en `8089:8089` y MongoDB únicamente en `127.0.0.1:27018:27017`. Los datos de MongoDB persisten en el volumen `mongodb_preferencias_data`. Para detener los servicios y conservar los datos:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```

## Ejecutar MS8 en el host

Crear `ticketya-net` como se indica arriba. Iniciar MongoDB usando el Compose de desarrollo:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mongodb-preferencias
```

Desde esta carpeta, iniciar la aplicación con el Maven Wrapper y la URI host de MongoDB:

```powershell
$env:PORT = "8089"; $env:MONGODB_URI = "mongodb://localhost:27018/ticketya_preferencias"; .\mvnw.cmd spring-boot:run
```

En Linux o macOS:

```sh
PORT=8089 MONGODB_URI=mongodb://localhost:27018/ticketya_preferencias ./mvnw spring-boot:run
```

La aplicación escucha en `http://localhost:8089`. Si no se define `MONGODB_URI`, esta misma URI de host es el valor predeterminado.

## Endpoints y documentación

Los endpoints de preferencias y recomendaciones requieren `X-User-Id` y `X-User-Roles` con el rol `CLIENTE`. Si falta alguno de los headers se responde `401`; si ambos están presentes pero los roles no incluyen `CLIENTE`, se responde `403`. Los roles se separan por comas, se recortan y se comparan sin distinguir mayúsculas.

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/preferencias/me` | `200` con las preferencias o listas vacías si no hay documento. |
| PUT | `/preferencias/me` | `200` con las preferencias reemplazadas. |
| DELETE | `/preferencias/me` | `204`; es idempotente. |
| GET | `/recomendaciones/me` | `200` con una lista de recomendaciones; si no hay eventos con puntuación positiva, devuelve `[]`. Un fallo del catálogo de MS1 produce `503`. |

Ejemplo de cuerpo PUT:

```json
{
  "tiposEvento": ["MUSICA"],
  "artistas": ["Artista de ejemplo"],
  "ciudades": ["Buenos Aires"]
}
```

- Healthcheck Actuator: <http://localhost:8089/actuator/health>
- Swagger UI: <http://localhost:8089/swagger-ui/index.html>
- Documento OpenAPI: <http://localhost:8089/v3/api-docs>

### Cómo se calculan las recomendaciones

MS8 combina las preferencias guardadas con el catálogo de eventos publicados de MS1 y, cuando Compras está disponible, el historial y las co-compras del cliente. Solo considera eventos futuros con estado `publicado` o `agotado` (sin distinguir mayúsculas) y devuelve como máximo 20 resultados, ordenados por puntuación descendente, fecha ascendente e ID ascendente.

| Coincidencia | Puntos |
|---|---:|
| Algún participante coincide con un artista favorito | 5 |
| Tipo del evento entre los tipos favoritos | 3 |
| Ciudad del evento entre las ciudades preferidas | 2 |
| Tipo del evento presente en el historial de compras | 1 |
| Evento presente en las co-compras consultadas | 2 |

Las comparaciones recortan espacios y no distinguen mayúsculas. Cada regla suma como máximo una vez por evento. Simplificaciones del alcance: solo se consultan co-compras para hasta cinco de los eventos comprados más recientes (Compras informa como máximo 20 IDs distintos); las cantidades no multiplican los puntos; si falla cualquier llamada de Compras se descartan todas sus señales y se recomienda solo con preferencias; los eventos ya comprados no se excluyen. Los fallos de MS1 no tienen fallback y responden `503`.Cuando Compras falla, la respuesta no indica que no se consideró el historial.

### Avisos de eventos publicados

La outbox de MS1 invoca `POST /internal/preferencias/eventos-publicados` directamente, sin pasar por el Gateway. Requiere `X-Service-Token` igual a `SERVICIO_TOKEN`; si falta, no coincide o MS8 no tiene configurado el token, responde `403`. No requiere headers de identidad. `X-Aviso-Id` es opcional: se usa como correlación, o se genera un UUID si falta o está vacío.

Ejemplo de body, con la fecha UTC que envía MS1:

```json
{
  "eventoId": "evento-123",
  "nombre": "Concierto",
  "tipo": "MUSICA",
  "participantes": ["Artista de ejemplo"],
  "ciudad": "Buenos Aires",
  "fechaHora": "2030-01-02T03:04:05Z"
}
```

Un body inválido produce `400`. MS8 selecciona a un cliente solo si coinciden **un artista y la ciudad**, sin distinguir mayúsculas ni espacios exteriores; no usa `tiposEvento`. Una lista de participantes vacía es válida y no genera destinatarios.

MS8 envía un POST por destinatario a `/internal/notificaciones`, con el token de servicio. Si todos se aceptan o no hay destinatarios, responde `202` con `{"destinatarios": n, "enviados": n}`. Si falla cualquier envío, intenta los restantes y responde `503` para que la outbox reintente. Notificaciones deduplica usando el identificador de acontecimiento estable, tipo y usuario.

Simplificaciones: MS8 recorre las preferencias existentes, procesa los envíos secuencialmente y no persiste avisos enviados; la deduplicación queda a cargo de Notificaciones. El campo `lugar` del aviso es la ciudad y `fecha` se formatea en `America/Argentina/Buenos_Aires`. Las llamadas usan timeouts de conexión de 2 segundos y lectura de 5 segundos.Como el envío es secuencial, con muchos destinatarios MS8 puede tardar más que el timeout de lectura de la outbox de MS1; en ese caso MS1 reintenta y Notificaciones deduplica los avisos ya aceptados.

## Variables de entorno

| Variable | Predeterminado | Uso |
|---|---|---|
| `PORT` | `8089` | Puerto HTTP de MS8; el Compose base lo usa también en el healthcheck. |
| `MONGODB_URI` | `mongodb://localhost:27018/ticketya_preferencias` en host | URI de MongoDB. Compose configura la URI interna `mongodb://mongodb-preferencias:27017/ticketya_preferencias`. |
| `SPRING_PROFILES_ACTIVE` | `docker` en Compose; sin forzar en host | Perfil Spring activo. |
| `EVENTOS_BASE_URL` | `http://localhost:8081` en host; `http://lugares:8081` en Compose | URL base del catálogo de MS1. |
| `COMPRAS_BASE_URL` | `http://localhost:8083` en host; `http://compras:8083` en Compose | URL base de los endpoints internos de Compras. |
| `NOTIFICACIONES_BASE_URL` | `http://localhost:8090` en host; `http://notificaciones:8090` en Compose | URL base del servicio de Notificaciones. |
| `SERVICIO_TOKEN` | Sin valor útil predeterminado | Valor exigido en el callback y enviado en `X-Service-Token` a Compras y Notificaciones. Si falta, MS8 registra un WARN al arrancar y rechaza el callback con `403`; si Compras no acepta la llamada, se usan solo preferencias. Compose lo toma del entorno del proceso que lo ejecuta. |

Para usar un token al ejecutar MS8 en el host, configúralo en el entorno antes de iniciar la aplicación:

```powershell
$env:SERVICIO_TOKEN = "token-local"
$env:EVENTOS_BASE_URL = "http://localhost:8081"
$env:COMPRAS_BASE_URL = "http://localhost:8083"
.\mvnw.cmd spring-boot:run
```

En Linux o macOS:

```sh
SERVICIO_TOKEN=token-local EVENTOS_BASE_URL=http://localhost:8081 \
  COMPRAS_BASE_URL=http://localhost:8083 ./mvnw spring-boot:run
```

Al usar Compose, exporta `SERVICIO_TOKEN` antes de `docker compose up`; no se incluye ninguna credencial en los archivos Compose. Los defaults de URLs apuntan a los nombres de servicio de la red compartida.

## Integración entre servicios

En la red compartida, el Gateway `gateway:8086` llega a MS8 mediante `MS8_URL=http://preferencias:8089`. Los nombres y puertos de los otros servicios confirmados son Reservas `reservas:8080`, Compras `compras:8083`, Fila virtual `fila-virtual:8088` y Lugares `lugares:8081`. MongoDB de MS8 permanece exclusivamente en la red privada de este Compose.

## Postman

La colección `postman/Preferencias.postman_collection.json` incluye las operaciones de preferencias y recomendaciones y una carpeta para el callback interno. Define `baseUrl` como `http://localhost:8089`; configura la variable `servicioToken` para la solicitud interna y, opcionalmente, habilita su header `X-Aviso-Id`.

### Probar avisos con el stub de Notificaciones

El stub existente `postman/notificaciones-stub.js` requiere Node.js y escucha en `8090` por defecto. En una terminal PowerShell independiente, usar un token de prueba y arrancarlo:

```powershell
$env:SERVICIO_TOKEN = "token-local"
$env:PORT = "8090"
node .\postman\notificaciones-stub.js
```

Iniciar MongoDB como se indica arriba y, en otra terminal, iniciar MS8 **en el host** con el mismo token y la URL del stub:

```powershell
$env:SERVICIO_TOKEN = "token-local"
$env:NOTIFICACIONES_BASE_URL = "http://localhost:8090"
$env:PORT = "8089"
.\mvnw.cmd spring-boot:run
```

En un entorno de prueba aislado, desde una tercera terminal cargar preferencias coincidentes para dos clientes y enviar el callback. El PUT reemplaza cualquier preferencia previa de esos IDs.

```powershell
$preferencias = '{"tiposEvento":[],"artistas":["Artista de ejemplo"],"ciudades":["Buenos Aires"]}'
foreach ($usuario in @("cliente-123", "cliente-456")) {
    Invoke-RestMethod -Method Put -Uri "http://localhost:8089/preferencias/me" `
        -Headers @{"X-User-Id"=$usuario; "X-User-Roles"="CLIENTE"} `
        -ContentType "application/json" -Body $preferencias
}
$evento = '{"eventoId":"evento-123","nombre":"Concierto","tipo":"MUSICA","participantes":["Artista de ejemplo"],"ciudad":"Buenos Aires","fechaHora":"2030-01-02T03:04:05Z"}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8089/internal/preferencias/eventos-publicados" `
    -Headers @{"X-Service-Token"="token-local"; "X-Aviso-Id"="aviso-123"} `
    -ContentType "application/json" -Body $evento
Invoke-RestMethod -Uri "http://localhost:8090/_stub/notificaciones"
```

El diagnóstico `GET /_stub/notificaciones` muestra avisos aceptados y reentregas. Para probar una falla parcial, configurar `PUT /_stub/fallar/cliente-456` y reenviar el callback: MS8 responde `503` después de intentar ambos destinatarios. Usar otro `eventoId` si se quiere distinguir los avisos de esta prueba; el stub deduplica los reintentos.

```powershell
Invoke-RestMethod -Method Put -Uri "http://localhost:8090/_stub/fallar/cliente-456"
Invoke-RestMethod -Method Delete -Uri "http://localhost:8090/_stub/fallar"
```

Los modos globales se cambian con `POST /_stub/modo/ok` (acepta), `POST /_stub/modo/error` (responde `500`) y `POST /_stub/modo/timeout` (no responde). Por ejemplo:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8090/_stub/modo/error"
Invoke-RestMethod -Method Post -Uri "http://localhost:8090/_stub/modo/ok"
```

`DELETE /_stub/notificaciones` limpia avisos y fallas por usuario; todos los datos del stub están en memoria. Si MS8 corre en Docker, `localhost:8090` apunta al contenedor de MS8, no al host: hay que configurar una dirección alcanzable desde ese contenedor. En Docker Desktop, usar NOTIFICACIONES_BASE_URL=http://host.docker.internal:8090.

### Probar el endpoint con el stub de Compras

El stub `postman/compras-stub.js` es un servidor Node.js local para ejercitar las señales de Compras sin iniciar el microservicio real. Requiere Node.js y escucha en el puerto `8083` por defecto. En una terminal PowerShell, configura el mismo token de desarrollo para el stub y MS8, y arráncalo:

```powershell
$env:PORT = "8083"
$env:SERVICIO_TOKEN = "token-local"
node .\postman\compras-stub.js
```

Inicia MS8 en el host con `COMPRAS_BASE_URL=http://localhost:8083` y el mismo `SERVICIO_TOKEN` (ver los comandos de ejecución en host). El stub ofrece datos en memoria; carga el historial del usuario y las co-compras con PUT, por ejemplo:

```sh
curl -X PUT http://localhost:8083/_stub/usuarios/cliente-123 \
  -H "Content-Type: application/json" \
  -d "{\"tipos\":[{\"tipo\":\"MUSICA\",\"cantidad\":3}],\"eventosComprados\":[\"evento-comprado-1\"]}"

curl -X PUT http://localhost:8083/_stub/eventos/evento-comprado-1/co-compras \
  -H "Content-Type: application/json" \
  -d "{\"coCompras\":[{\"eventoId\":\"evento-recomendado\",\"cantidad\":4}]}"
```

Solicita `GET /recomendaciones/me` con `X-User-Id: cliente-123` y `X-User-Roles: CLIENTE`. MS1 debe tener eventos futuros con estado publicado o agotado para que aparezcan en la respuesta. Los modos de prueba se cambian con `POST /_stub/modo/ok`, `POST /_stub/modo/error` (el stub responde 503) y `POST /_stub/modo/timeout` (no responde, ejercitando el timeout del cliente). `GET /_stub/llamadas` permite inspeccionar llamadas internas y `DELETE /_stub/datos` limpia los datos y el registro de llamadas. Los datos del stub se pierden al reiniciarlo.
