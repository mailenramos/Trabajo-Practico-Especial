# TicketYa — Gestión de notificaciones

Microservicio de la plataforma TicketYa (Arquitectura Web, TUDAI, UNICEN) que avisa por correo al cliente cuando se confirma una compra, se resuelve una devolución, se cancela o modifica un evento o se publica un evento de su interés. Es el MS9 y escucha en el puerto 8090.

Qué hace:

- Recibe avisos de otros servicios en `POST /internal/notificaciones`, los guarda en estado `PENDIENTE` y responde `202` sin enviar el correo en la misma solicitud.
- Es idempotente por acontecimiento + tipo + destinatario: un aviso reentregado no crea otra notificación.
- Una tarea programada toma las pendientes (con una actualización condicional `PENDIENTE → PROCESANDO`, así dos instancias no envían la misma), pide el correo del destinatario a Gestión de usuarios y lo envía por SMTP.
- Reintenta los fallos temporales con espera creciente hasta un máximo; después pasa a `ERROR_FINAL`. Un destinatario inexistente o sin correo pasa a `ERROR_FINAL` sin reintentos.
- Otra tarea recupera las notificaciones que quedaron en `PROCESANDO` tras una caída.
- Usa timeouts y circuit breaker (Resilience4j) hacia Gestión de usuarios y SMTP.

Qué no hace: no tiene endpoints públicos, no usa broker de mensajes, no envía SMS ni push y **no garantiza entrega exactamente una vez** (si SMTP acepta el correo y se pierde la confirmación, un reintento puede enviarlo dos veces).

La API está documentada en `src/main/resources/static/openapi.yaml` (servida en `/openapi.yaml`). Es de uso interno, así que declara solo `TokenDeServicio` (`X-Service-Token`); el único endpoint sin token es `/actuator/health`.

### Colección de Postman

Está en [`postman/`](postman/): `TicketYa-MS9-Notificaciones.postman_collection.json` y el environment `TicketYa-MS9-Local.postman_environment.json`. Se importan los dos, se completa `servicioToken` (el `SERVICIO_TOKEN` del `.env`) y se corre la colección con el Runner: obtiene el `usuarioId` real del cliente de prueba, envía un aviso de cada uno de los 8 tipos, prueba la idempotencia y los errores, y consulta Mailpit. Como Docker no publica el puerto 8090, desde la raíz del repo:

```powershell
docker compose -f docker-compose.yml -f Backend/mcsv-notificaciones/docker-compose.dev.yml up -d notificaciones
```

## Requisitos

- **Java 21**. No hace falta instalar Maven: el proyecto trae el wrapper (`mvnw`).
- **MySQL 8** (motor a confirmar por el grupo). La base `ticketya_notificaciones` y la tabla se crean solas al arrancar.
- Un **servidor SMTP**. Para desarrollo alcanza con uno de pruebas como [Mailpit](https://mailpit.axllent.org/) (SMTP en el puerto 1025, interfaz web en el 8025).

## Variables de entorno

| Variable | Qué es | Por defecto |
| --- | --- | --- |
| `PUERTO` | Puerto HTTP del servicio | `8090` |
| `DB_URL` | URL JDBC de la base | `jdbc:mysql://localhost:3306/ticketya_notificaciones?createDatabaseIfNotExist=true&serverTimezone=UTC` |
| `DB_USER` | Usuario de la base | `root` |
| `DB_PASSWORD` | Contraseña de la base | vacía |
| `DB_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | `update` |
| `SERVICIO_TOKEN` | Token compartido entre servicios (encabezado `X-Service-Token`). **Sin este valor el endpoint interno rechaza todas las llamadas con 403** | vacío |
| `USUARIOS_URL` | URL de Gestión de usuarios y autenticación (MS6). Llamada directa, no por el Gateway | `http://localhost:8087` (puerto a confirmar) |
| `USUARIOS_TIMEOUT_CONEXION` | Timeout de conexión hacia MS6 | `2s` |
| `USUARIOS_TIMEOUT_LECTURA` | Timeout de lectura hacia MS6 | `5s` |
| `SMTP_HOST` | Servidor SMTP | `localhost` |
| `SMTP_PORT` | Puerto SMTP | `1025` |
| `SMTP_USUARIO` | Usuario SMTP | vacío |
| `SMTP_CLAVE` | Contraseña SMTP | vacía |
| `SMTP_AUTH` | ¿El servidor pide autenticación? | `false` |
| `SMTP_STARTTLS` | ¿Usar STARTTLS? | `false` |
| `SMTP_TIMEOUT_CONEXION_MS` | Timeout de conexión SMTP (ms) | `2000` |
| `SMTP_TIMEOUT_LECTURA_MS` | Timeout de lectura y escritura SMTP (ms) | `5000` |
| `SMTP_REMITENTE` | Dirección del remitente | `no-responder@ticketya.local` |
| `NOTIF_MAX_INTENTOS` | Intentos de envío antes de `ERROR_FINAL` | `5` |
| `NOTIF_ESPERA_BASE` | Espera tras el primer fallo; se duplica en cada intento | `30s` |
| `NOTIF_ESPERA_MAXIMA` | Tope de la espera entre intentos | `1h` |
| `NOTIF_ENVIO_ACTIVO` | Activa las tareas programadas (`false` las apaga; los tests lo usan) | `true` |
| `NOTIF_ENVIO_INTERVALO` | Cada cuánto corre la tarea de envío | `10s` |
| `NOTIF_ENVIO_LOTE` | Notificaciones que toma cada ronda | `20` |
| `NOTIF_PLAZO_PROCESAMIENTO` | Tiempo máximo en `PROCESANDO` antes de recuperarla | `2m` |
| `NOTIF_INTERVALO_RECUPERACION` | Cada cuánto corre la tarea de recuperación | `60s` |

Las duraciones aceptan el formato de Spring (`500ms`, `30s`, `2m`, `1h`).

## Cómo ejecutarlo

Desde `Backend/mcsv-notificaciones`, en PowerShell:

```powershell
$env:DB_PASSWORD = 'tu-contraseña'
$env:SERVICIO_TOKEN = 'un-token-compartido'
.\mvnw spring-boot:run
```

Con Docker:

```bash
docker build -t ticketya/notificaciones .
docker run -p 8090:8090 \
  -e DB_URL='jdbc:mysql://mysql:3306/ticketya_notificaciones?createDatabaseIfNotExist=true&serverTimezone=UTC' \
  -e DB_USER=root -e DB_PASSWORD=secreto \
  -e SERVICIO_TOKEN=un-token-compartido \
  -e USUARIOS_URL=http://usuarios:8087 \
  -e SMTP_HOST=mailpit -e SMTP_PORT=1025 \
  ticketya/notificaciones
```

Ejemplo de aviso:

```bash
curl -i -X POST http://localhost:8090/internal/notificaciones \
  -H 'Content-Type: application/json' -H 'X-Service-Token: un-token-compartido' \
  -d '{"acontecimientoId":"compra-confirmada-c1","tipo":"COMPRA_CONFIRMADA","versionEsquema":1,
       "ocurridoEn":"2026-11-17T12:00:00Z","recursoId":"c1","usuarioId":"u-123",
       "correlacionId":"corr-1","datos":{"evento":"Recital","cantidad":2,"total":"30000"}}'
```

## Tests

```powershell
.\mvnw test
```

Usan H2 en memoria (modo MySQL): no hace falta MySQL, MS6 ni SMTP. Gestión de usuarios se prueba contra un servidor HTTP local y SMTP con un doble.

## Contratos

Acordados: `POST /internal/notificaciones` (este servicio) y `GET /internal/usuarios/{id}/contacto` en MS6.

A acordar con el grupo:

- Forma exacta del aviso y campos de `datos` por tipo (la propuesta está en el OpenAPI).
- Forma de la respuesta de MS6: se asume `{"correo": "..."}`; 404 = el usuario no existe.
- Mecanismo de autorización entre servicios (hoy, token compartido en `X-Service-Token`, que también se envía a MS6).
- Servidor SMTP y remitente, motor de base de datos y puerto.
