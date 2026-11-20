# TicketYa — Microservicio 3: Compras y Devoluciones

Microservicio de la plataforma TicketYa (Arquitectura Web, TUDAI, UNICEN) que administra las **compras** de entradas y las **devoluciones**, y coordina ambos procesos con los otros servicios: Eventos (1), Reservas (2), Pagos (4), Entradas (5), Recomendaciones (8) y Notificaciones (9).

Qué hace:

- Inicia una compra a partir de una reserva, calcula el total y coordina el cobro. Según el resultado, confirma la venta y pide la emisión de las entradas, o libera los lugares.
- Permite reintentar un pago sin duplicar la compra (clave de idempotencia por cliente).
- Registra solicitudes de devolución, que un administrador aprueba o rechaza, y coordina el reintegro.
- Devuelve automáticamente las compras de un evento cancelado.
- Recibe de Eventos los cambios de fecha, horario o lugar de un evento y actualiza las fechas guardadas.
- Avisa a Notificaciones cada cambio relevante (compra confirmada, devolución, reintegro, evento cancelado o modificado) mediante un outbox: el aviso se guarda con la operación y una tarea lo envía después.
- Le da a Recomendaciones dos consultas sobre las compras confirmadas (co-compras e historial por tipo de evento).

Qué no hace: no crea reservas, no procesa cobros ni reintegros y no emite entradas. Eso lo hacen los otros servicios, a los que este llama directamente (sin pasar por el Gateway).

## Requisitos

- **Java 21** (la compilación falla con una versión anterior). Verificá con `java -version` y que `JAVA_HOME` apunte al JDK 21.
- **MySQL 8**, corriendo en `localhost:3306`.
- No hace falta instalar Maven: el proyecto trae el wrapper (`mvnw`).

## Configuración

La configuración está en `src/main/resources/application.properties`. Los valores que cambian según el entorno se leen de variables de entorno:

| Variable | Qué es | Por defecto |
| --- | --- | --- |
| `DB_URL` | URL JDBC de la base (en Docker, la del contenedor de MySQL) | `jdbc:mysql://localhost:3306/ticketya_compras?createDatabaseIfNotExist=true&serverTimezone=UTC` |
| `DB_USER` | Usuario de MySQL | `root` |
| `DB_PASSWORD` | Contraseña de MySQL | vacía |
| `RESERVAS_URL` | URL del servicio de Reservas | `http://localhost:8080` |
| `EVENTOS_URL` | URL del servicio de Eventos | `http://localhost:8081` |
| `PAGOS_URL` | URL del servicio de Pagos | `http://localhost:8084` |
| `ENTRADAS_URL` | URL del servicio de Entradas | `http://localhost:8085` |
| `NOTIFICACIONES_URL` | URL del servicio de Notificaciones | `http://localhost:8090` |
| `SERVICIO_TOKEN` | Token compartido entre servicios. Los endpoints `/internal/**` lo exigen en el encabezado `X-Service-Token`, y se envía en los avisos a Notificaciones. **Sin valor, todas las llamadas internas responden 403** y Notificaciones rechaza los avisos | vacía |

El servicio escucha en el puerto **8083**. La base `ticketya_compras` y sus tablas se crean solas la primera vez que arranca (`createDatabaseIfNotExist` y `ddl-auto=update`), así que no hay que correr ningún script.

Otras propiedades útiles (con su valor por defecto):

| Propiedad | Qué controla | Valor |
| --- | --- | --- |
| `compras.emision.intentos` | Intentos de emisión de entradas antes de dejar la compra pendiente | `3` |
| `compras.idempotencia.plazo-en-proceso` | Tiempo tras el cual se puede retomar un pago interrumpido | `60s` |
| `compras.cancelacion.plazo-en-proceso` | Tiempo tras el cual la revisión automática (o un aviso repetido) retoma una cancelación de evento interrumpida | `5m` |
| `compras.cancelacion.intervalo-reanudacion` | Cada cuánto se revisan las cancelaciones en proceso vencidas para retomarlas | `60s` |
| `compras.cancelacion.reanudacion-activa` | Activa la revisión automática de cancelaciones (`false` la desactiva; las pruebas la desactivan) | `true` |
| `compras.rol-administrador` | Rol que, si está entre los de `X-User-Roles`, identifica al administrador | `ADMIN` |
| `compras.avisos.envio-activo` | Activa la tarea que envía los avisos a Notificaciones (`false` la desactiva; las pruebas la desactivan) | `true` |
| `compras.avisos.intervalo-envio` | Cada cuánto corre la tarea de envío de avisos | `10s` |
| `compras.avisos.intentos-maximos` | Intentos de envío de un aviso antes de dejarlo en `ERROR_FINAL` | `5` |
| `compras.avisos.espera-base` | Espera antes del segundo intento; se duplica en cada intento siguiente | `30s` |
| `compras.avisos.espera-maxima` | Tope de la espera entre intentos | `30m` |
| `compras.avisos.plazo-procesando` | Tiempo tras el cual un aviso que quedó enviándose (el servicio se cayó) vuelve a pendiente | `2m` |
| `compras.avisos.lote` | Avisos que se envían como máximo en cada ronda | `50` |
| `compras.zona-horaria` | Zona horaria de las fechas y horarios que van en los avisos | `America/Argentina/Buenos_Aires` |
| `servicios.pagos.rutas.cobro` | Ruta de Pagos para cobrar | `/internal/pagos` |
| `servicios.pagos.rutas.pagos-por-compra` | Ruta de Pagos para listar los pagos de una compra | `/internal/pagos/compra/{purchaseId}` |
| `servicios.pagos.rutas.reintegro` | Ruta de Pagos para pedir un reintegro | `/internal/pagos/{paymentId}/reintegros` |
| `servicios.pagos.rutas.reintento-reintegro` | Ruta de Pagos para reintentar un reintegro fallido | `/internal/reintegros/{refundId}/reintentar` |
| `servicios.pagos.rutas.consulta-reintegro` | Ruta de Pagos para consultar un reintegro | `/internal/reintegros/{refundId}` |

Las rutas de Pagos deben empezar con `/` (si no, el servicio no arranca) y conservar su única variable entre llaves; el nombre de la variable no importa, se reemplaza por posición.

## Cómo ejecutarlo

Desde la carpeta `Backend/mcsv-compras-devoluciones` (relativa a la raíz del repositorio), en PowerShell (Windows):

```powershell
$env:DB_USER = 'root'
$env:DB_PASSWORD = 'tu-contraseña'
$env:SERVICIO_TOKEN = 'el-token-acordado-por-el-grupo'
.\mvnw spring-boot:run
```

Usá comillas simples en la contraseña: con comillas dobles, PowerShell interpreta caracteres como `$`. En Linux o macOS:

```bash
export DB_USER=root
export DB_PASSWORD='tu-contraseña'
export SERVICIO_TOKEN='el-token-acordado-por-el-grupo'
./mvnw spring-boot:run
```

Para que `DB_USER` y `DB_PASSWORD` queden guardadas entre sesiones en Windows, usá `[Environment]::SetEnvironmentVariable('DB_PASSWORD', 'tu-contraseña', 'User')` y reiniciá la terminal o el editor.

Cuando arranca bien, la consola muestra que Tomcat escucha en el puerto 8083.

### Imagen de Docker

El `Dockerfile` compila el jar con Maven y Java 21 en una etapa y lo corre con el JRE 21 en otra, con un usuario sin privilegios. No lleva secretos: toda la configuración se pasa al ejecutar el contenedor, con las variables de la tabla de arriba (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVICIO_TOKEN`, `NOTIFICACIONES_URL` y las URLs de los otros servicios).

```bash
docker build -t ticketya/compras .
```

La imagen expone el puerto 8083, pero no hay que publicarlo (`-p`) fuera de la red de Docker: este servicio confía en los encabezados de identidad del Gateway y solo el Gateway debe llegar a él.

### Ejecutarlo junto a los otros servicios

Las operaciones de compra, pago y devolución llaman a Eventos, Reservas, Pagos y Entradas. Para probar el flujo completo, esos servicios tienen que estar corriendo en las URLs de la tabla de arriba (o hay que cambiarlas con las variables de entorno). Si alguno no responde, el servicio informa un error 503 (servicio no disponible) y no deja la operación a medias.

Sin los otros servicios, igual se pueden consultar compras y devoluciones (listas vacías) y revisar la documentación de la API.

## Pruebas

```powershell
.\mvnw test
```

Corre más de 300 pruebas: de los repositorios, de los servicios, de los endpoints de punta a punta y de los clientes de los otros servicios. Los otros servicios se reemplazan por dobles de prueba, así que no hace falta tenerlos corriendo. Lo único necesario es MySQL con `DB_USER` y `DB_PASSWORD` configuradas: las pruebas crean y borran su propia base `ticketya_compras_test`, y una de ellas arranca la aplicación contra `ticketya_compras`.

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
| `POST /internal/eventos-modificados` | Eventos (interno, `X-Service-Token`) | Aviso de cambio de fecha, horario o lugar de un evento |
| `GET /internal/eventos/{eventoId}/co-compras` | Recomendaciones (interno, `X-Service-Token`) | Otros eventos comprados por quienes compraron este |
| `GET /internal/usuarios/{usuarioId}/historial-preferencias` | Recomendaciones (interno, `X-Service-Token`) | Tipos de evento y eventos comprados por el cliente |

Medios de pago admitidos: `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER` y `DIGITAL_WALLET`.

### Identificación del usuario

Desde la Entrega 2, el API Gateway valida el JWT del usuario, descarta los encabezados de identidad que manda el cliente y propaga estos dos. Este servicio no recibe ni valida el token:

- `X-User-Id`: identificador del usuario (el `sub` del token). Es obligatorio; sin él responde 401 `USUARIO_NO_IDENTIFICADO`.
- `X-User-Roles`: roles del usuario separados por coma; puede haber varios o ninguno. Se comparan sin distinguir mayúsculas ni espacios. Es administrador quien tiene `ADMIN` entre sus roles, aunque tenga otros (por ejemplo `CLIENTE,ADMIN`); sin ese rol, las operaciones de administrador responden 403 `SOLO_ADMINISTRADOR`.

Las reglas de titularidad no cambian: un cliente ve y opera solo sus compras y devoluciones, según `X-User-Id`, tenga los roles que tenga.

**Compatibilidad:** si no llega `X-User-Roles` pero sí el encabezado anterior `X-User-Role` (un solo rol, de la Entrega 1), se lo toma como único rol. Si llegan los dos, manda `X-User-Roles`. `X-User-Role` está obsoleto y se retira cuando todo el grupo use el Gateway nuevo.

**Seguridad:** el servicio confía en estos encabezados, así que solo debe ser alcanzable a través del Gateway. En la configuración de entrega (Docker), el puerto 8083 no debe publicarse fuera de la red `ticketya-net`; solo el Gateway llega a él. Esa configuración todavía no está en el repositorio. Los endpoints `/internal/**` no pasan por el Gateway y usan `X-Service-Token`.

Un ejemplo de consulta, como la reenviaría el Gateway:

```powershell
curl.exe -H "X-User-Id: cliente-1" -H "X-User-Roles: CLIENTE" http://localhost:8083/compras
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
├── modelo/        entidades JPA (compras, detalle, solicitudes, reintegros, claves de idempotencia, cancelaciones, modificaciones de eventos, avisos pendientes)
├── repositorio/   repositorios Spring Data y transiciones de estado atómicas
├── servicio/      lógica de compra, pago, devolución, reintegro y cancelación
├── cliente/       clientes de Eventos, Reservas, Pagos, Entradas y Notificaciones (interfaz + implementación REST)
├── tarea/         tareas programadas (reanudación de cancelaciones y envío de avisos)
├── web/           controladores, DTOs, identificación del usuario, permisos y token de servicio
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
- Los avisos a Notificaciones se guardan en la misma transacción que la operación (outbox) y se envían después: si Notificaciones no responde, la compra, la devolución o el reintegro se completan igual y el aviso se reintenta con espera creciente.
- La modificación de un evento es idempotente por evento y versión: la misma versión, o una anterior a la ya procesada, no cambia nada.
