# Gestión de preferencias (MS8)

Microservicio Spring Boot 3.4.4 y Java 21 para consultar, reemplazar y eliminar las preferencias de eventos, artistas y ciudades de un cliente. Persiste en su propia base MongoDB standalone `ticketya_preferencias`.

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

Las tres operaciones requieren `X-User-Id` y `X-User-Roles` con el rol `CLIENTE`:

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/preferencias/me` | `200` con las preferencias o listas vacías si no hay documento. |
| PUT | `/preferencias/me` | `200` con las preferencias reemplazadas. |
| DELETE | `/preferencias/me` | `204`; es idempotente. |

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

## Variables de entorno

| Variable | Predeterminado | Uso |
|---|---|---|
| `PORT` | `8089` | Puerto HTTP de MS8; el Compose base lo usa también en el healthcheck. |
| `MONGODB_URI` | `mongodb://localhost:27018/ticketya_preferencias` en host | URI de MongoDB. Compose configura la URI interna `mongodb://mongodb-preferencias:27017/ticketya_preferencias`. |
| `SPRING_PROFILES_ACTIVE` | `docker` en Compose; sin forzar en host | Perfil Spring activo. |

## Integración entre servicios

En la red compartida, el Gateway `gateway:8086` llega a MS8 mediante `MS8_URL=http://preferencias:8089`. Los nombres y puertos de los otros servicios confirmados son Reservas `reservas:8080`, Compras `compras:8083`, Fila virtual `fila-virtual:8088` y Lugares `lugares:8081`. MongoDB de MS8 permanece exclusivamente en la red privada de este Compose.

## Postman

La colección `postman/Preferencias.postman_collection.json` contiene GET, PUT y DELETE. Define `baseUrl` como `http://localhost:8089` y envía los headers de identidad `X-User-Id` y `X-User-Roles`.
