# TicketYa — Microservicio 3: Compras y Devoluciones

Microservicio de la plataforma TicketYa (Arquitectura Web, TUDAI, UNICEN) que administra las **compras** de entradas y las **devoluciones**, y coordina ambos procesos con los otros servicios: Eventos (1), Reservas (2), Pagos (4) y Entradas (5).

Qué hace:

- Inicia una compra a partir de una reserva, calcula el total y coordina el cobro. Según el resultado, confirma la venta y pide la emisión de las entradas, o libera los lugares.
- Permite reintentar un pago sin duplicar la compra (clave de idempotencia por cliente).
- Registra solicitudes de devolución, que un administrador aprueba o rechaza, y coordina el reintegro.
- Devuelve automáticamente las compras de un evento cancelado.

Qué no hace: no crea reservas, no procesa cobros ni reintegros y no emite entradas. Eso lo hacen los otros servicios, a los que este llama directamente (sin pasar por el Gateway).

## Requisitos

- **Java 21** (la compilación falla con una versión anterior). Verificá con `java -version` y que `JAVA_HOME` apunte al JDK 21.
- **MySQL 8**, corriendo en `localhost:3306`.
- No hace falta instalar Maven: el proyecto trae el wrapper (`mvnw`).

## Configuración

La configuración está en `src/main/resources/application.properties`. Los valores que cambian según el entorno se leen de variables de entorno:

| Variable | Qué es | Por defecto |
| --- | --- | --- |
| `DB_USER` | Usuario de MySQL | `root` |
| `DB_PASSWORD` | Contraseña de MySQL | vacía |
| `RESERVAS_URL` | URL del servicio de Reservas | `http://localhost:8080` |
| `EVENTOS_URL` | URL del servicio de Eventos | `http://localhost:8081` |
| `PAGOS_URL` | URL del servicio de Pagos | `http://localhost:8084` |
| `ENTRADAS_URL` | URL del servicio de Entradas | `http://localhost:8085` |

El servicio escucha en el puerto **8083**. La base `ticketya_compras` y sus tablas se crean solas la primera vez que arranca (`createDatabaseIfNotExist` y `ddl-auto=update`), así que no hay que correr ningún script.

Otras propiedades útiles (con su valor por defecto):

| Propiedad | Qué controla | Valor |
| --- | --- | --- |
| `compras.emision.intentos` | Intentos de emisión de entradas antes de dejar la compra pendiente | `3` |
| `compras.idempotencia.plazo-en-proceso` | Tiempo tras el cual se puede retomar un pago interrumpido | `60s` |
| `compras.cancelacion.plazo-en-proceso` | Tiempo tras el cual la revisión automática (o un aviso repetido) retoma una cancelación de evento interrumpida | `5m` |
| `compras.cancelacion.intervalo-reanudacion` | Cada cuánto se revisan las cancelaciones en proceso vencidas para retomarlas | `60s` |
| `compras.cancelacion.reanudacion-activa` | Activa la revisión automática de cancelaciones (`false` la desactiva; las pruebas la desactivan) | `true` |
| `compras.rol-administrador` | Valor de `X-User-Role` que identifica al administrador | `ADMIN` |

## Cómo ejecutarlo

Desde la carpeta `Backend/mcsv-compras-devoluciones` (relativa a la raíz del repositorio), en PowerShell (Windows):

```powershell
$env:DB_USER = 'root'
$env:DB_PASSWORD = 'tu-contraseña'
.\mvnw spring-boot:run
```

Usá comillas simples en la contraseña: con comillas dobles, PowerShell interpreta caracteres como `$`. En Linux o macOS:

```bash
export DB_USER=root
export DB_PASSWORD='tu-contraseña'
./mvnw spring-boot:run
```

Para que `DB_USER` y `DB_PASSWORD` queden guardadas entre sesiones en Windows, usá `[Environment]::SetEnvironmentVariable('DB_PASSWORD', 'tu-contraseña', 'User')` y reiniciá la terminal o el editor.

Cuando arranca bien, la consola muestra que Tomcat escucha en el puerto 8083.

### Ejecutarlo junto a los otros servicios

Las operaciones de compra, pago y devolución llaman a Eventos, Reservas, Pagos y Entradas. Para probar el flujo completo, esos servicios tienen que estar corriendo en las URLs de la tabla de arriba (o hay que cambiarlas con las variables de entorno). Si alguno no responde, el servicio informa un error 503 (servicio no disponible) y no deja la operación a medias.

Sin los otros servicios, igual se pueden consultar compras y devoluciones (listas vacías) y revisar la documentación de la API.

## Pruebas

```powershell
.\mvnw test
```

Corre más de 150 pruebas: de los repositorios, de los servicios, de los endpoints de punta a punta y de los clientes de los otros servicios. Los otros servicios se reemplazan por dobles de prueba, así que no hace falta tenerlos corriendo. Lo único necesario es MySQL con `DB_USER` y `DB_PASSWORD` configuradas: las pruebas crean y borran su propia base `ticketya_compras_test`, y una de ellas arranca la aplicación contra `ticketya_compras`.

Incluyen pruebas de concurrencia (dos pagos simultáneos de la misma reserva, dos solicitudes de devolución de la misma entrada) que verifican que no se duplica ningún efecto.

## API

La documentación completa (rutas, parámetros, ejemplos de pedido y respuesta, códigos de respuesta y de error) está en formato OpenAPI en [`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml). Con el servicio corriendo, también se publica en `http://localhost:8083/openapi.yaml`. Para verla en forma visual, pegá ese archivo en <https://editor.swagger.io>.

| Método y ruta | Quién | Qué hace |
| --- | --- | --- |
| `POST /compras` | Cliente | Inicia una compra desde una reserva (`{"reservaId": "..."}`) |
| `GET /compras` | Cliente | Lista sus compras |
| `GET /compras/{compraId}` | Cliente | Detalle de una compra propia |
| `POST /compras/{compraId}/pago` | Cliente | Paga una compra. Requiere el encabezado `Idempotency-Key` y `{"medioDePago": "..."}` |
| `POST /devoluciones` | Cliente | Solicita la devolución de una entrada (`{"entradaId": "..."}`) |
| `GET /devoluciones` | Cliente o administrador | Lista solicitudes (el cliente, las suyas; el administrador, todas) |
| `GET /devoluciones/{devolucionId}` | Titular o administrador | Detalle de una solicitud |
| `POST /devoluciones/{devolucionId}/aprobacion` | Administrador | Aprueba y coordina la devolución |
| `POST /devoluciones/{devolucionId}/rechazo` | Administrador | Rechaza con un motivo |
| `GET /reintegros` | Administrador | Lista reintegros, con filtro por estado |
| `POST /reintegros/{reintegroId}/reintento` | Administrador | Reintenta un reintegro fallido o pendiente |
| `POST /eventos-cancelados` | Eventos (interno) | Aviso de cancelación de un evento |

Medios de pago admitidos: `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER` y `DIGITAL_WALLET`.

### Identificación del usuario

En la Entrega 1 no hay autenticación. El usuario llega identificado por dos encabezados que, en el sistema completo, propaga el API Gateway:

- `X-User-Id`: identificador del usuario. Es obligatorio; sin él responde 401.
- `X-User-Role`: rol del usuario. El administrador es `ADMIN`.

Un ejemplo de consulta:

```powershell
curl.exe -H "X-User-Id: cliente-1" http://localhost:8083/compras
```

### Formato de errores

Todos los errores usan el formato acordado por el grupo:

```json
{
  "timestamp": "2026-11-08T22:00:00Z",
  "status": 409,
  "error": "COMPRA_YA_INICIADA",
  "mensaje": "La reserva ya tiene una compra"
}
```

El código del error va en `error`, y los errores de validación (400) suman un objeto `campos` con el detalle de cada campo inválido.

## Estructura del proyecto

```
Backend/mcsv-compras-devoluciones/src/main/java/ticketya/
├── modelo/        entidades JPA (compras, detalle, solicitudes, reintegros, claves de idempotencia, cancelaciones)
├── repositorio/   repositorios Spring Data y transiciones de estado atómicas
├── servicio/      lógica de compra, pago, devolución, reintegro y cancelación
├── cliente/       clientes de Eventos, Reservas, Pagos y Entradas (interfaz + implementación REST)
├── web/           controladores, DTOs, identificación del usuario y permisos
└── error/         manejo global de errores
Backend/mcsv-compras-devoluciones/src/test/java/ticketya/
└── dobles/        dobles de prueba de los otros servicios
```

## Diseño y especificaciones

El servicio se desarrolló con **desarrollo guiado por especificaciones (OpenSpec)**: cada capacidad se escribió primero como especificación, con un diseño y una lista de tareas, y recién después se implementó y se archivó. Todo está en la carpeta [`openspec`](../../openspec) de la raíz del repositorio; la documentación, en [`docs`](../../docs).

- [`openspec/specs/`](../../openspec/specs): especificaciones vigentes del servicio (compra, pago, consulta, devolución, reintegros y cancelación de eventos).
- [`openspec/changes/archive/`](../../openspec/changes/archive): los cambios ya implementados, cada uno con su propuesta, diseño y tareas.
- [`docs/historias-ms3.md`](../../docs/historias-ms3.md): historias de usuario con criterios de aceptación, estados, contratos con los otros servicios y decisiones.
- [`docs/diagramas-ms3.md`](../../docs/diagramas-ms3.md): diagrama de secuencia de la compra y diagrama entidad-relación del servicio. Los archivos fuente (`.mmd`) y las imágenes (`.png`) están en [`docs/diagramas-ms3/`](../../docs/diagramas-ms3).

Las decisiones de diseño más importantes:

- El cambio de estado de una compra, una solicitud o una cancelación es una actualización condicional en la base, así que dos pedidos simultáneos no pueden aplicar el mismo efecto dos veces.
- El pago es idempotente. La clave (`Idempotency-Key`) vale solo para el cliente que la envió, y a Pagos se le manda una clave de cobro propia. Un pago interrumpido se retoma desde el último paso hecho, sin volver a cobrar.
- Cada reintegro se guarda antes de pedirlo a Pagos, con un identificador único. Un reintegro fallido se reintenta sobre el mismo reintegro, nunca creando uno nuevo.
- Una aprobación de devolución que queda incompleta (por la falla de otro servicio) no se revierte: se registra en qué paso quedó y un administrador la retoma.
- Una compra pagada cuya venta rechaza Reservas (la reserva venció entre el cobro y la confirmación) queda fallida y se reintegra el total automáticamente.
