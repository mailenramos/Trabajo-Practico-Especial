# msvc-pagos

Microservicio interno Spring Boot 3.4.5 / Java 17 para registrar pagos y reintegros de TicketYa; lo consume el servicio de Compras.

## Documentación

- Diseño del servicio (modelo de datos, idempotencia, concurrencia): [docs/diseno-pagos.md](docs/diseno-pagos.md)
- Contrato de la API: Swagger UI en `/swagger-ui/index.html`

## Requisitos y ejecución

Se requiere Java 17, PostgreSQL y la base `ticketya_payments`. La aplicación escucha en el puerto `8084` y toma la conexión de `DB_HOST` (por defecto `localhost`), `DB_PORT` (por defecto `5432`), `DB_NAME` (por defecto `ticketya_payments`), `DB_USER` (por defecto `postgres`) y `DB_PASSWORD` (obligatoria, sin valor por defecto).

### Inicio reproducible en Windows (PowerShell)

Para que cualquier integrante del equipo pueda levantar una base local con las mismas credenciales de desarrollo, desde esta carpeta ejecutá:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d postgres
$config = Get-Content .env | ConvertFrom-StringData
$env:JAVA_TOOL_OPTIONS = '-Duser.timezone=UTC'
$env:DB_HOST = 'localhost'
$env:DB_PORT = $config.DB_PORT
$env:DB_NAME = $config.DB_NAME
$env:DB_USER = $config.DB_USER
$env:DB_PASSWORD = $config.DB_PASSWORD
.\mvnw.cmd spring-boot:run
```

`.env.example` contiene credenciales de demostración, solo para desarrollo local. Se copia a `.env`, que no se versiona; cada integrante puede usar ese valor de ejemplo o cambiarlo en `.env`. No compartan contraseñas personales ni usen estas credenciales en producción. PostgreSQL aplica `POSTGRES_PASSWORD` solo al inicializar un volumen vacío: cambiar `.env` después no cambia la clave de una base ya creada. Si ya hay otro contenedor usando el puerto `5435`, detenelo antes de ejecutar Compose.

En PowerShell, configurar `DB_PASSWORD` en el entorno y ejecutar:

```powershell
.\mvnw.cmd spring-boot:run
```

Para iniciar el entorno local de Windows, ejecutar desde la carpeta del servicio. Este ejemplo usa el contenedor existente `ticketya-pagos-postgres`, publicado en el puerto `5435`, y un JDK 17 o superior:

```powershell
docker start ticketya-pagos-postgres
$env:JAVA_HOME = "C:\ruta\a\tu\JDK-17-o-superior"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
$env:DB_HOST = "localhost"
$env:DB_PORT = "5435"
$env:DB_NAME = "ticketya_payments"
$env:DB_USER = "postgres"
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new("", (Read-Host "Contraseña PostgreSQL" -AsSecureString)).Password
.\mvnw.cmd spring-boot:run
```

Reemplazar la ruta de `JAVA_HOME` por la del JDK instalado. La contraseña ingresada debe ser la configurada al inicializar el volumen de PostgreSQL; recrear el contenedor conservando ese volumen no cambia la contraseña.

Hibernate actualiza las tablas y `schema.sql` agrega el índice parcial de pagos aprobados. Swagger UI está disponible en `/swagger-ui/index.html`.

### Imagen Docker

Desde esta carpeta:

```powershell
docker build -t ticketya/msvc-pagos .
```

Al ejecutar el contenedor, configurar las variables `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` y `DB_PASSWORD`, y conectarlo a la red privada donde está PostgreSQL y el servicio de Compras. `EXPOSE 8084` documenta el puerto del proceso; no publica el puerto del host por sí mismo. Pagos es interno y no se debe publicar directamente en Internet ni enrutar desde el Gateway. La orquestación completa con `docker-compose.yml` corresponde a la plataforma y no se modifica desde este servicio.

Cada solicitud acepta `X-Request-Id` solo si es un UUID; si falta o no es válido genera uno y lo devuelve en la respuesta. El log HTTP incluye ID, método, path, status y duración. Los logs de operaciones críticas identifican pagos/reintegros sin incluir claves de idempotencia ni datos sensibles; salen por consola para que la plataforma los recolecte.

El perfil de pruebas usa la base aislada `ticketya_payments_test`, `DB_PASSWORD` y `spring.jpa.hibernate.ddl-auto=create-drop`. Crear primero la base vacía en pgAdmin. Las pruebas PostgreSQL de concurrencia se ejecutan optativamente con `RUN_POSTGRESQL_TESTS=true`.

Para correr la suite completa, incluidas las pruebas PostgreSQL, configurar `DB_PASSWORD` y ejecutar en PowerShell:

```powershell
$env:RUN_POSTGRESQL_TESTS = "true"
.\mvnw.cmd clean verify
```

## Pagos

- `POST /internal/pagos` crea un pago. Requiere `Idempotency-Key` y un cuerpo con `purchaseId`, `amount` positivo de hasta dos decimales y `paymentMethod`.
- `GET /internal/pagos/{paymentId}` consulta un pago.
- `GET /internal/pagos/compra/{purchaseId}` lista los pagos de una compra y responde una lista vacía con HTTP 200 si no hay resultados.

Una creación nueva responde 201; una repetición idempotente responde 200. Las solicitudes inválidas responden 400, una consulta inexistente 404 y un conflicto de compra o clave 409. Un pago rechazado se reintenta con una **Idempotency-Key nueva**. `DIGITAL_WALLET` siempre se rechaza; para intentar un pago aprobado después de ese rechazo, se debe usar otro medio de pago.

`CREDIT_CARD`, `DEBIT_CARD` y `BANK_TRANSFER` siguen `ticketya.payment-simulation.payment-decision`, que acepta `APPROVED` o `REJECTED` y por defecto es `APPROVED`. La aplicación falla al iniciar si la propiedad tiene otro valor.

## Reintegros

- `POST /internal/pagos/{paymentId}/reintegros` crea un reintegro; requiere `Idempotency-Key` y cuerpo con `refundRequestId`, `amount` y `reason` obligatorio.
- `GET /internal/reintegros/{refundId}` consulta el reintegro.
- `POST /internal/reintegros/{refundId}/reintentar` reintenta un reintegro `FALLIDO` y **no** recibe `Idempotency-Key`.

Un reintegro nuevo responde 201 y una repetición idempotente 200. El saldo suma reintegros `EXITOSO` y `PENDIENTE`, excluye `FALLIDO` y acepta el acumulado exactamente igual al importe cobrado. Un `409` en `/reintentar` indica que el reintegro ya no está `FALLIDO`; el cliente debe consultar `GET /internal/reintegros/{refundId}` para conocer su estado.

La decisión del simulador se configura con `ticketya.payment-simulation.refund-decision` (`SUCCESS` o `FAILURE`). La aplicación falla al iniciar si la propiedad tiene otro valor. Un reintento responde 200 e incrementa `attemptCount`: ante fallo conserva `FALLIDO`, y ante éxito pasa a `EXITOSO`.

## Códigos y errores

Los errores usan `{timestamp, status, error, mensaje, campos?}`; `timestamp` está en formato ISO-8601 y `campos` aparece solo en errores de validación HTTP 400.

| HTTP | Uso |
|---|---|
| 200 | Consulta, repetición idempotente o reintento resuelto |
| 201 | Pago o reintegro nuevo |
| 400 | Solicitud, header o validación inválida |
| 404 | Pago o reintegro inexistente |
| 409 | Conflicto de idempotencia, saldo, compra aprobada o reintento no permitido |
| 500 | Error interno genérico, sin exponer detalles de la excepción |

## Datos de demostración

Al iniciar fuera del perfil `test`, `DataInitializer` agrega registros fijos únicamente si ambas tablas están vacías:

| Registro | UUID | Clave |
|---|---|---|
| Pago aprobado | `11111111-1111-4111-8111-111111111111` | `seed-payment-approved` |
| Pago rechazado | `22222222-2222-4222-8222-222222222222` | `seed-payment-rejected` |
| Reintegro exitoso | `33333333-3333-4333-8333-333333333333` | `seed-refund-success` |
| Reintegro fallido (para probar retry) | `44444444-4444-4444-8444-444444444444` | `seed-refund-failed` |

El pago aprobado corresponde a `purchaseId` `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa`; el rechazado, a `bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb`.

## Evolución a una pasarela real

La simulación actual resuelve cada operación en una transacción. Una pasarela externa real requerirá un flujo de dos fases: persistir una operación pendiente, comunicarse con el proveedor fuera de la transacción y resolverla mediante callback o reconciliación. Ese flujo no forma parte de esta implementación.

## Decisiones de diseño

- Cada operación se crea y resuelve en una transacción única. `EN_PROCESO` y `PENDIENTE` existen en los enums, pero no se persisten; una pasarela real requeriría dos fases.
- La idempotencia compara una huella SHA-256 del payload normalizado.
- Un índice parcial garantiza como máximo un pago aprobado por compra.
- Los reintegros bloquean el pago con `PESSIMISTIC_WRITE` para serializar validaciones de saldo y reintentos.
