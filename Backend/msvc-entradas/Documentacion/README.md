# Microservicio 5 – Gestión de Entradas

## 1. Descripción general

El microservicio de Gestión de Entradas forma parte de TicketYa, una plataforma de venta de entradas para eventos.

Su responsabilidad principal es emitir y administrar las entradas una vez que una compra fue confirmada por el microservicio de Compras.

También permite consultar entradas, gestionar reintegros y registrar devoluciones.

## 2. Tecnologías utilizadas

- Java.
- Spring Boot.
- Spring Data JPA.
- PostgreSQL 16.
- Flyway para el control de versiones y migraciones de la base de datos.
- Maven Wrapper (`mvnw.cmd` en Windows); no hace falta instalar Maven.

## 3. Configuración

### Inicio local en Windows (PowerShell)

Requisitos: JDK 17 o superior y PostgreSQL disponible en `localhost:5433`, con la base `entradas` y las credenciales configuradas abajo. No hace falta instalar Maven: el wrapper descarga Maven 3.10.0 la primera vez, por lo que se requiere Internet. Desde la raíz del repositorio:

En una instalación nueva, crea primero PostgreSQL para desarrollo local:

```powershell
docker run --name ticketya-entradas-postgres --restart unless-stopped -e POSTGRES_DB=entradas -e POSTGRES_USER=entradas -e POSTGRES_PASSWORD=entradas -p 5433:5432 -d postgres:16
```

En siguientes ejecuciones, si el contenedor ya existe, iniciálo con `docker start ticketya-entradas-postgres`. El contenedor conserva sus datos; no lo elimines ni borres su volumen para reiniciarlo.

Luego inicia MS5:

```powershell
cd .\Backend\msvc-entradas
.\mvnw.cmd spring-boot:run
```

La aplicación escucha en `http://localhost:8085`.

| Propiedad | Valor |
|---|---|
| Nombre | `msvc-entradas` |
| Puerto | `8085` |
| Base de datos | PostgreSQL |
| Base de datos (nombre) | `entradas` |
| Gestión de migraciones | Flyway |
| Estrategia de Hibernate | `validate` |

La aplicación utiliza Flyway para mantener actualizada la estructura de la base de datos y Hibernate para validar que las entidades coincidan con el esquema existente.

### Ejecución local en Windows (PowerShell)

Se necesita un JDK 17 o superior y PostgreSQL disponible en `localhost:5433`. No hace falta instalar Maven: el wrapper descarga Maven 3.10.0 automáticamente la primera vez (se necesita conexión a Internet). Desde la carpeta `Backend\msvc-entradas`:

```powershell
.\mvnw.cmd spring-boot:run
```

También se puede ejecutar `.\mvnw.cmd test` para las pruebas. En Linux o macOS, usar `./mvnw`.

### Variables de configuración

| Variable | Predeterminado | Descripción |
|---|---|---|
| `SERVICE_TOKEN` | vacío | Secreto compartido para `X-Service-Token` en `/internal/**`. Vacío = validación desactivada (se avisa al arrancar), mientras el grupo define si todos los servicios lo validan. |
| `MS1_URL` | `http://localhost:8081` | Lugares, sectores y eventos (autorización del personal de acceso). |
| `MS1_CONNECT_TIMEOUT` / `MS1_READ_TIMEOUT` | `1s` / `2s` | Tiempos máximos de la consulta a MS1. Si se exceden, la validación responde 503. |

## 4. Endpoints

### 4.1. Endpoints públicos (vía API Gateway)

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| GET | `/entradas?estado=&idEvento=&idCliente=&page=&size=` | `CLIENTE`, `ADMIN` | `CLIENTE`: solo sus entradas (`idCliente` se ignora). `ADMIN`: todas, con filtro opcional por `idCliente`. |
| GET | `/entradas/{id}` | `CLIENTE`, `ADMIN` | `CLIENTE`: solo una propia. `ADMIN`: cualquiera. |
| POST | `/eventos/{idEvento}/ingresos` | `PERSONAL_ACCESO`, `ADMIN` | Valida el QR (`{"codigoQr": "<uuid>"}`) y registra el ingreso. Ver sección 6. |

**Encabezados de identidad** (los completa el API Gateway a partir del JWT; elimina los que envíe el cliente):

- `X-User-Id`: identificador del usuario. Si falta: `401 FALTA_IDENTIFICACION`.
- `X-User-Roles`: todos los roles, separados por coma. Si no llega, se usa `X-User-Role` (Entrega 1), que el Gateway envía solo para usuarios con un único rol.

Un usuario con solo `PERSONAL_ACCESO` no puede consultar entradas (`403`): su operación es la validación.

### 4.2. Endpoints internos (solo Compras y devoluciones)

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/internal/entradas/emision` | Emite las entradas de una compra confirmada. Idempotente por `idCompra`. `409 EVENTO_CERRADO` si el evento ya se cerró y la compra es nueva. |
| GET | `/internal/entradas/{id}` | Consulta una entrada. |
| GET | `/internal/compras/{idCompra}/entradas` | `{idCompra, entradas: [...]}` con las entradas de la compra y sus estados, sin emitir nada. Lista vacía = no se emitió. |
| POST | `/internal/entradas/{id}/reintegro` | Bloqueo por devolución: `VIGENTE` → `EN_REINTEGRO`. Idempotente. `409 ENTRADA_UTILIZADA` si la entrada ya se usó. |
| POST | `/internal/entradas/{id}/devolucion` | `EN_REINTEGRO` → `DEVUELTA`. Idempotente. |
| POST | `/internal/eventos/{idEvento}/entradas/reintegro` | Cierre del evento cancelado (ver sección 7): `{idEvento, entradasMarcadas, cerrado: true}`. Idempotente. |

El API Gateway no enruta `/internal/**` (responde 404). Si `SERVICE_TOKEN` está configurado, estas rutas exigen `X-Service-Token` con ese valor; si falta o no coincide: `401 SERVICIO_NO_AUTENTICADO`. El token nunca se escribe en el log.

**Correlación:** cada pedido acepta `X-Correlation-Id` (UUID) o genera uno; se devuelve en la respuesta, se registra en el log y se reenvía en la llamada a MS1.

## 5. Emisión de entradas

La emisión se realiza cuando el microservicio de Compras informa que una compra fue confirmada.

**Endpoint:** `POST /internal/entradas/emision`

La petición incluye:

- `idCompra`: identificador único de la compra.
- `idCliente`: identificador del cliente.
- `evento`: información resumida del evento.
- `lugares`: listado de lugares adquiridos. Cada uno con `idSector`, `nombreSector`, `fila` y `numero` (nulos en sectores no numerados).

Los identificadores de eventos y sectores son cadenas de texto porque pertenecen al modelo de datos del microservicio de Eventos.
`comienzaEn` representa un instante, compatible con `Instant` de MS3; se envía y devuelve como fecha ISO-8601 con zona u offset, por ejemplo `2026-12-15T21:00:00Z`.

### Ejemplo de petición

```json
{
  "idCompra": "a1234567-1234-4234-8234-123456789001",
  "idCliente": "cliente-prueba-01",
  "evento": {
    "id": "evento-prueba-01",
    "nombre": "Recital de prueba",
    "comienzaEn": "2026-12-15T21:00:00Z"
  },
  "lugares": [
    { "idSector": "sector-prueba-01", "nombreSector": "Platea", "fila": 1, "numero": 6 },
    { "idSector": "sector-prueba-01", "nombreSector": "Platea", "fila": 1, "numero": 7 }
  ]
}
```

### Idempotencia

La emisión es idempotente mediante `idCompra`: si se repite para una compra ya emitida, devuelve las mismas entradas (HTTP 200), incluso si el evento se cerró después. Con datos distintos responde `409 IDEMPOTENCIA_CONFLICTO`.

Un asiento numerado no puede tener dos entradas `VIGENTE` o `UTILIZADA` en el mismo evento (índice único parcial `uk_entrada_lugar_ocupado`): un asiento usado sigue ocupado.

## 6. Validación del QR y control de acceso

El código QR de cada entrada es un UUID aleatorio (`codigoQr`). El servicio no genera la imagen: la puede generar el frontend a partir del código.

`POST /eventos/{idEvento}/ingresos` con `{"codigoQr": "..."}`:

1. **Rol:** `PERSONAL_ACCESO` o `ADMIN`; si no, `403 ACCESO_DENEGADO`.
2. **Asignación al evento** (solo sin `ADMIN`): se consulta a MS1 en cada validación, sin caché, `GET /internal/eventos/{idEvento}/personal-acceso/{usuarioId}/autorizacion`. Con `autorizado: false` (sin asignación activa o evento inexistente): `403 PERSONAL_NO_AUTORIZADO`. Si MS1 no responde, responde con error o excede el tiempo: `503 AUTORIZACION_NO_DISPONIBLE` (rechazo temporal, se puede reintentar). Un `ADMIN` valida sin asignación y el ingreso queda marcado como `excepcional`.
3. **Entrada:** QR inexistente: `404 ENTRADA_NO_ENCONTRADA`; de otro evento: `409 ENTRADA_DE_OTRO_EVENTO`; evento cerrado: `409 EVENTO_CERRADO`.
4. **Registro atómico:** `UPDATE ... SET estado = 'UTILIZADA' WHERE id = ? AND estado = 'VIGENTE'` y alta en `ingresos` (una por entrada, con restricción única) en la misma transacción. Si la actualización no aplica: `409 ENTRADA_YA_UTILIZADA` (informa la fecha del ingreso anterior) o `409 ENTRADA_NO_HABILITADA` (`EN_REINTEGRO` o `DEVUELTA`).

La autorización se comprueba antes de buscar la entrada, para que un usuario no autorizado no pueda averiguar si un QR existe. Una validación que ya obtuvo la autorización puede completarse aunque la asignación se revoque mientras se procesa.

**Respuesta 201:**

```json
{
  "idIngreso": "…", "idEntrada": "…", "idEvento": "evento-prueba-01",
  "sector": { "id": "sector-prueba-01", "nombre": "Platea" }, "fila": 1, "numero": 6,
  "validadoPor": "00000000-0000-4000-8000-000000000004", "excepcional": false,
  "validadoEl": "2026-12-15T20:30:00Z"
}
```

## 7. Estados de las entradas y cierre del evento

| Estado | Descripción |
|---|---|
| `VIGENTE` | Entrada emitida y válida para ingresar. |
| `UTILIZADA` | Se validó su QR en el ingreso. Estado final. |
| `EN_REINTEGRO` | Bloqueada por una devolución aprobada o por la cancelación del evento; su QR ya no permite ingresar. |
| `DEVUELTA` | El reintegro se completó. Estado final. |

```
VIGENTE ──validación QR──► UTILIZADA
   │
   └──devolución / cancelación──► EN_REINTEGRO ──reintegro completado──► DEVUELTA
```

Solo una de las dos transiciones que salen de `VIGENTE` puede completarse (actualizaciones condicionales). Una entrada `EN_REINTEGRO` nunca vuelve automáticamente a `VIGENTE`.

### Cierre del evento

Cuando se cancela un evento, Compras llama a `POST /internal/eventos/{idEvento}/entradas/reintegro`. En una sola transacción:

1. Se crea (si no existe) la fila de `control_eventos` y se bloquea con `FOR UPDATE`.
2. Se registra `cerrado_el` (solo la primera vez).
3. Las entradas `VIGENTE` pasan a `EN_REINTEGRO`. Las `UTILIZADA` conservan su estado.

La emisión y la validación toman un bloqueo compartido (`FOR SHARE`) sobre la misma fila: pueden ejecutarse a la vez entre sí, pero el cierre espera a que terminen las que están en curso, y las que llegan después ven el evento cerrado y responden `409 EVENTO_CERRADO`. El cierre se registra aunque el evento no tenga entradas, es idempotente y siempre responde `cerrado: true`.

## 8. Manejo de errores

Los errores se centralizan mediante `GlobalExceptionHandler` con el formato acordado por el grupo:

```json
{
  "timestamp": "2026-11-09T18:30:00Z",
  "status": 404,
  "error": "ENTRADA_NO_ENCONTRADA",
  "mensaje": "No existe la entrada indicada"
}
```

| Campo | Descripción |
|---|---|
| `timestamp` | Fecha y hora del error. |
| `status` | Código de estado HTTP. |
| `error` | Código de negocio del error. |
| `mensaje` | Descripción del error. |
| `campos` | Detalles de validación, cuando corresponda. |

| Código | HTTP |
|---|---:|
| `VALIDACION` | 400 |
| `FALTA_IDENTIFICACION`, `SERVICIO_NO_AUTENTICADO` | 401 |
| `ACCESO_DENEGADO`, `PERSONAL_NO_AUTORIZADO` | 403 |
| `ENTRADA_NO_ENCONTRADA` | 404 |
| `TRANSICION_INVALIDA`, `ENTRADA_VIGENTE_DUPLICADA`, `IDEMPOTENCIA_CONFLICTO` | 409 |
| `ENTRADA_UTILIZADA`, `ENTRADA_YA_UTILIZADA`, `ENTRADA_NO_HABILITADA`, `ENTRADA_DE_OTRO_EVENTO`, `EVENTO_CERRADO` | 409 |
| `AUTORIZACION_NO_DISPONIBLE` | 503 |

## 9. Pruebas

```powershell
.\mvnw.cmd test
```

| Clase | Qué cubre | Requiere Docker |
|---|---|---|
| `EntradaServiceImplTest` | Emisión, idempotencia, cierre, `ENTRADA_UTILIZADA`, consulta por compra y permisos por rol. | No |
| `IngresoServiceTest` | Reglas de la validación de QR: roles, asignación, evento, estados y rechazos. | No |
| `EntradaControllerTest` | HTTP: encabezados de identidad, `X-Service-Token`, `X-Correlation-Id` y mapeo de errores. | No |
| `AutorizacionAccesoClienteRestTest` | Cliente de MS1: respuesta, encabezados enviados y fallas → 503. | No |
| `EntradaConcurrencyIntegrationTest` | Migraciones y emisión concurrente del mismo asiento en PostgreSQL. | Sí |
| `IngresoConcurrencyIntegrationTest` | Validaciones simultáneas del mismo QR, validación contra bloqueo por devolución, emisión contra cierre, cierre idempotente y asiento utilizado ocupado. | Sí |

Las clases que usan Testcontainers se saltean si Docker no está disponible. Para ejecutarlas, iniciá Docker Desktop antes de `.\mvnw.cmd test`.

La colección Postman de la Entrega 2 es `postman/TicketYa-Entradas-Entrega2.postman_collection.json`.

## 10. Integración con otros microservicios

- **Compras y devoluciones (MS3):** emisión, consulta por entrada y por compra, bloqueo por devolución, devolución y cierre del evento. Envía `X-Service-Token` y `X-Correlation-Id`.
- **Lugares, sectores y eventos (MS1):** consulta la asignación del personal de acceso en cada validación. Contrato: siempre `200 {eventoId, usuarioId, autorizado}`.
- **API Gateway:** enruta `GET /entradas`, `GET /entradas/{id}` y `POST /eventos/{eventoId}/ingresos`, y propaga la identidad del JWT.

El servicio funciona en el puerto `8085` y no utiliza `context-path` ni reescritura de rutas.

## 11. Documentación de la Entrega 2

- User stories: [docs/historias-ms5-entrega2.md](../../../docs/historias-ms5-entrega2.md).
- Diseño y tareas: [openspec/changes/validacion-qr-entrega2](../openspec/changes/validacion-qr-entrega2).
- Contrato OpenAPI: [openapi-entradas.yaml](../openapi-entradas.yaml).
