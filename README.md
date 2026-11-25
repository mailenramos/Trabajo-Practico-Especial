# TicketYa — Trabajo Práctico Especial

Plataforma de venta de entradas para eventos (Arquitectura Web, TUDAI, UNICEN). Está formada por nueve microservicios Spring Boot, un API Gateway y un frontend en Vue 3. Cada microservicio tiene su propia base de datos y su propio README dentro de `Backend/`.

## Servicios

| | Servicio (Compose) | Puerto interno | Base de datos | Carpeta |
|---|---|---:|---|---|
| MS1 – Lugares, sectores y eventos | `lugares` | 8081 | MongoDB | `Backend/mcsv-lugares-sectores-eventos` |
| MS2 – Disponibilidad y reservas | `reservas` | 8080 | MySQL | `Backend/mcsv-disponibildad-reservas` |
| MS3 – Compras y devoluciones | `compras` | 8083 | MySQL | `Backend/mcsv-compras-devoluciones` |
| MS4 – Pagos | `pagos` | 8084 | PostgreSQL | `Backend/mcvs-pagos` |
| MS5 – Entradas | `entradas` | 8085 | PostgreSQL | `Backend/msvc-entradas` |
| MS6 – Usuarios y autenticación | `usuarios` | 8087 | PostgreSQL | `Backend/mcsv-usuarios-autenticacion` |
| MS7 – Fila virtual | `fila-virtual` | 8088 | MySQL | `Backend/mcsv-fila-virtual` |
| MS8 – Preferencias y recomendaciones | `preferencias` | 8089 | MongoDB | `Backend/mcsv-preferencias-recomendaciones` |
| MS9 – Notificaciones | `notificaciones` | 8090 | MySQL | `Backend/mcsv-notificaciones` |
| API Gateway | `gateway` | 8086 | — | `Backend/APIGateWay` |

Servicios de apoyo incluidos en el Compose general: **Mailpit** (SMTP de prueba para Notificaciones) y **Loki + Alloy + Grafana** (recolección y consulta de logs, en `observabilidad/`).

Los clientes entran solo por el Gateway, que valida el JWT emitido por MS6 y reenvía la identidad a cada servicio en `X-User-Id` y `X-User-Roles`. Los microservicios se llaman entre sí directamente por nombre de servicio (`http://lugares:8081`, etc.), sin pasar por el Gateway, y se autentican con el header `X-Service-Token`.

## Levantar todo con Docker Compose

### Requisitos

- **Docker Desktop** con Docker Compose 2.20 o superior (el Compose general usa `include`).
- **Memoria:** el sistema completo son unos 25 contenedores (10 JVM y 8 bases). Con 16 GB de RAM, asigná al menos 10 GB a Docker. En Windows (WSL 2), creá `%UserProfile%\.wslconfig` con:

  ```ini
  [wsl2]
  memory=10GB
  ```

  y aplicalo con `wsl --shutdown` antes de volver a abrir Docker Desktop. Con menos memoria, los servicios se pausan y los pedidos pueden vencer por timeout.
- **Git Bash** (o cualquier bash) para el script de prueba de punta a punta.

### Pasos

Desde la raíz del repositorio:

1. Crear la red compartida (una sola vez por instalación de Docker):

   ```sh
   docker network create ticketya-net
   ```

2. Crear el `.env` de la raíz a partir del ejemplo y completar los valores vacíos:

   ```sh
   cp .env.example .env        # PowerShell: Copy-Item .env.example .env   |   CMD: copy .env.example .env
   ```

   | Variable | Uso |
   |---|---|
   | `SERVICIO_TOKEN` | Token compartido del header `X-Service-Token`; mismo valor en todos los servicios. |
   | `JWT_SECRET` | Secreto de los JWT, de 32 caracteres o más; lo usan MS6 (firma) y el Gateway (validación). |
   | `DB_PASSWORD` | Contraseña de las bases de Compras, Notificaciones, Pagos, Entradas y Usuarios (cada una tiene su propia base). |
   | `MYSQL_ROOT_PASSWORD` | Root de MySQL de Compras y Notificaciones. |
   | `MS2_DB_PASSWORD`, `MS2_MYSQL_ROOT_PASSWORD` | Base de Reservas. |
   | `MS7_DB_PASSWORD`, `MS7_MYSQL_ROOT_PASSWORD` | Base de Fila virtual. |
   | `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Administrador inicial de MS6 (contraseña de 8 caracteres o más, con letras y números). |
   | `USUARIOS_BASE_URL`, `SMTP_HOST`, `SMTP_PORT` | Ya vienen completas (`http://usuarios:8087`, `mailpit`, `1025`). |

   Opcionales: `GATEWAY_RESPONSE_TIMEOUT` (por defecto `15s`), `GATEWAY_CORS_ALLOWED_ORIGINS` (por defecto `http://localhost:5173`) y `JWT_ISSUER` (por defecto `ticketya-usuarios`).

   > El `.env` de la raíz se aplica a todos los Compose incluidos y tiene prioridad sobre los `.env` de cada carpeta. No agregues ahí `PORT`, `SERVER_PORT`, `PUERTO`, `DB_USER`, `DB_URL`, `DB_NAME`, `DB_HOST` ni `DB_PORT`: varios servicios usan esos nombres con valores distintos.

3. Construir y levantar:

   ```sh
   docker compose up -d --build
   docker compose ps
   ```

   La primera construcción descarga las dependencias de Maven y puede tardar entre 15 y 30 minutos. El arranque completo puede tardar varios minutos más: mientras arranca, es normal ver servicios en `health: starting` o `unhealthy`. Cuando terminan, todos los que tienen healthcheck quedan en `healthy`; el Gateway y los servicios de observabilidad sin healthcheck figuran como `Up`.

### Qué queda expuesto

| URL | Qué es |
|---|---|
| <http://localhost:8086> | API Gateway: única entrada para clientes y frontend. |
| <http://localhost:8025> | Mailpit: bandeja de los correos que envía Notificaciones. |
| <http://localhost:3000> | Grafana: dashboard **TicketYa → TicketYa - Logs**. |
| <http://localhost:12345> | Alloy: estado del pipeline de logs (diagnóstico). |

Los microservicios y las bases no publican puertos en el host. Para consultar un servicio sin pasar por el Gateway, usá `docker compose exec`, por ejemplo:

```sh
docker compose exec lugares wget -qO- http://localhost:8081/eventos
```

### Usuarios de prueba

MS6 crea al arrancar un usuario por rol, todos con la contraseña `Prueba123`:

| Correo | Rol |
|---|---|
| `cliente@ticketya.test` | CLIENTE |
| `organizador@ticketya.test` | ORGANIZADOR |
| `admin@ticketya.test` | ADMIN |
| `personal@ticketya.test` | PERSONAL_ACCESO |

Para obtener un token:

```sh
curl -X POST http://localhost:8086/auth/login -H "Content-Type: application/json" -d '{"correo":"cliente@ticketya.test","password":"Prueba123"}'
```

y enviarlo como `Authorization: Bearer <token>`. La búsqueda y el detalle de eventos (`GET /eventos`, `GET /eventos/{eventoId}`) son públicos.

### Prueba de punta a punta

`scripts/prueba-conjunta.sh` recorre el flujo completo por el Gateway: login de los tres roles, alta de lugar y sector (ADMIN), creación, precio y publicación de un evento (ORGANIZADOR), reserva, compra y pago (CLIENTE) y modificación del evento. Desde Git Bash, con todo en `healthy`:

```sh
bash scripts/prueba-conjunta.sh
```

Cada paso muestra su código HTTP y se detiene ante el primer resultado inesperado. Al terminar, en Mailpit tienen que aparecer para `cliente@ticketya.test` los correos de entradas disponibles, compra confirmada y cambios en el evento (el último puede tardar uno o dos minutos).

### Datos de demostración para la interfaz

Con una base nueva el catálogo está vacío. `scripts/cargar-datos-demo.sh` crea por el Gateway 2 lugares con sectores (numerado y general) y 4 eventos (teatro, música, rock y deporte), les pone precio y los publica. Publicar es lo que inicializa la disponibilidad en MS2, por eso quedan listos para reservar y comprar desde la interfaz. Desde Git Bash, con todo en `healthy`:

```sh
bash scripts/cargar-datos-demo.sh
```

El script entra como `admin@ticketya.test` (solo un admin puede crear lugares y sectores) y como `organizador@ticketya.test` para los eventos, ambos con `Prueba123`. Para que el admin de prueba exista, `ADMIN_EMAIL` del `.env` no debe ser `admin@ticketya.test`. Es repetible: omite los eventos que ya existen por nombre y reutiliza los lugares que ya existen con el mismo nombre y dirección.

> Los eventos de ejemplo del perfil `dev` de MS1 no sirven para comprar: se guardan ya publicados sin pasar por la API, y MS2 nunca recibe su disponibilidad.

### Logs y trazabilidad

Todos los servicios escriben sus logs en la consola con el `X-Correlation-Id` del pedido. El Gateway lo genera si el cliente no lo envía y los servicios lo propagan en sus llamadas a otros servicios. Alloy recolecta los logs de los contenedores (excepto las bases), Loki los guarda siete días y Grafana los muestra. En el dashboard **TicketYa - Logs** se filtra por servicio, nivel o texto: buscar un `correlationId` muestra el recorrido de un pedido por todos los servicios. El script de prueba usa IDs `pc-<hora>-<paso>` para facilitar esa búsqueda.

### Detener y volver a levantar

```sh
docker compose stop      # detiene todo y conserva contenedores y datos
docker compose start     # vuelve a levantar lo detenido
docker compose down      # elimina los contenedores; los datos quedan en los volúmenes
docker compose down -v   # elimina también los volúmenes: BORRA TODOS LOS DATOS
```

### Equipos con menos memoria

El sistema completo necesita unos 6 GB de memoria para los contenedores y bastante CPU durante el arranque, porque las diez JVM arrancan a la vez. Se recomiendan 16 GB de RAM con 10 GB asignados a Docker (ver Requisitos). Con menos recursos hay dos alternativas, que se pueden combinar:

**Arrancar en etapas**, para evitar el pico de CPU:

```sh
docker compose up -d mongodb mongodb-preferencias mysql-compras mysql-reservas mysql-fila-virtual mysql-notificaciones pagos-db postgres-entradas postgres-usuarios mailpit
docker compose up -d usuarios lugares reservas
# esperar a que estén healthy (docker compose ps)
docker compose up -d pagos entradas compras notificaciones
# esperar a que estén healthy
docker compose up -d
```

**Levantar solo el flujo principal** (sin observabilidad, Fila virtual ni Preferencias):

```sh
docker compose up -d gateway usuarios lugares reservas compras pagos entradas notificaciones mailpit
```

Las bases de esos servicios se levantan solas. El flujo de compra funciona igual; los avisos dirigidos a los servicios que no están levantados quedan pendientes en los outbox y se reintentan cuando se los inicia.

### Problemas comunes

- **`network ticketya-net declared as external, but could not be found`**: falta el paso 1 (`docker network create ticketya-net`).
- **`required variable ... is missing a value`**: falta completar esa variable en el `.env` de la raíz.
- **Servicios `unhealthy` durante varios minutos o pedidos lentos**: suele ser falta de memoria o CPU. Revisá `docker stats --no-stream`, cerrá otras aplicaciones pesadas y asigná más memoria a Docker (ver Requisitos).
- **`504 Gateway Timeout`**: el servicio tardó más que `GATEWAY_RESPONSE_TIMEOUT`. Publicar un evento, por ejemplo, llama a Reservas y a Preferencias antes de responder. En máquinas lentas se puede subir en el `.env` (por ejemplo, `30s`) y aplicar con `docker compose up -d gateway`.
- **Un servicio llama a URLs equivocadas (por ejemplo, `host.docker.internal`)**: si una carpeta de `Backend/` tiene su propio `.env` (por ejemplo, para probar con simuladores), el Compose general toma de ahí las variables que el `.env` de la raíz no define. Comentá esas URLs y recreá el servicio con `docker compose up -d <servicio>`.
- **Una base rechaza la contraseña**: la contraseña se fija al crear el volumen. Si el volumen se creó antes con otra, hay que volver a la contraseña original o borrar el volumen (`docker compose down -v`, que elimina los datos).
- **`container name ... is already in use` o servicios duplicados**: hay un Compose individual de algún servicio levantado. Bajalo con `docker compose down` dentro de su carpeta antes de usar el Compose general.
- **Puerto ocupado (8086, 8025, 3000 o 12345)**: detené el proceso o contenedor que lo usa.
- **Git falla con `Rename from '.../.git/index.lock' ... failed`** (repositorio dentro de OneDrive): pausá la sincronización de OneDrive y reintentá.

## Frontend

La interfaz de cliente está hecha con Vue 3 y Vite y usa el Gateway en `http://localhost:8086`. Requiere Node.js 20.19+ o 22.12+:

```sh
cd Frontend
npm ci          # solo la primera vez
npm run dev
```

Abrí <http://localhost:5173>. El flujo de usuario está descrito en [Frontend/README.md](Frontend/README.md).

## Ejecutar un servicio fuera de Docker

Para desarrollar un microservicio en particular, cada README de `Backend/` explica cómo correrlo desde el host con `mvnw` y cómo levantar solo su base. Los `docker-compose.dev.yml` de cada carpeta publican, únicamente en desarrollo, el puerto de la aplicación (y en algunos casos el de su base) en el host.

## Documentación

- `Backend/*/README.md`: configuración, API, decisiones y pruebas de cada servicio.
- `openspec/` y las carpetas `openspec/` de cada servicio: especificaciones y cambios (desarrollo guiado por especificaciones).
- `docs/`: documentación general del proyecto.
