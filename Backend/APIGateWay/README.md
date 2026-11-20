# TicketYa API Gateway — Entrega 2

Gateway HTTP para las operaciones públicas de Eventos/Lugares, Disponibilidad/Reservas, Compras/Devoluciones y Entradas. Conserva los paths de los servicios, no agrega `/api/v1` y escucha en `http://localhost:8086` por defecto. Valida JWT, aplica autorización por rol y propaga una identidad derivada exclusivamente del token.

## Requisitos y ejecución

- JDK 17 o superior.
- No hace falta instalar Maven: el wrapper descarga Maven 3.10.0 la primera vez que se ejecuta. Se requiere conexión a Internet para esa descarga.

Desde esta carpeta:

```powershell
$env:JWT_SECRET = "<clave aleatoria compartida con el emisor JWT, mínimo 32 bytes UTF-8>"
mvn spring-boot:run
```

Para ejecutar toda la suite automatizada (pruebas unitarias y de integración):

```powershell
.\mvnw.cmd clean verify
```

Las pruebas usan servidores HTTP falsos y no requieren levantar MS1–MS5, MongoDB, MySQL ni Docker. Cubren el encaminamiento por servicio, preservación de la solicitud/respuesta, exclusión de rutas internas, autenticación JWT, autorización, propagación segura de identidad, CORS y fallos de conexión.

## Pruebas manuales con Postman

Importá estos dos archivos en Postman:

- `postman/TicketYa-Gateway.postman_collection.json`
- `postman/TicketYa-Gateway-Local.postman_environment.json`

Seleccioná el environment **TicketYa - Gateway local**. Por defecto, `gatewayUrl` es `http://localhost:8086`. Pegá el JWT del emisor configurado en la variable secreta `accessToken`; las solicitudes protegidas requieren un usuario/rol habilitado. Las carpetas **03 - Reserva** y **04 - Compra y entradas** requieren completar IDs y levantar los servicios/dependencias indicados en sus descripciones.

Para una reserva, completá `eventoId` con un evento publicado con disponibilidad y `sectorId` con un sector numerado libre; ajustá también `fila` y `numero`. El request guarda `reservaId` si MS2 lo devuelve. Para continuar, usá esa reserva al iniciar la compra; al crearla se guarda `compraId`. El pago usa una clave de idempotencia nueva por request. Ejecutá cada operación de escritura deliberadamente: crea datos y puede reservar lugares o procesar un pago.

Iniciá MongoDB/MySQL y los microservicios necesarios siguiendo el README de cada uno. Para consultar Eventos/Lugares, MS1 y MongoDB deben estar disponibles; para el flujo completo de compra y emisión también deben responder los servicios y bases de datos requeridos por MS2, MS3, MS4 y MS5. Finalmente iniciá el Gateway en esta carpeta:

```powershell
.\mvnw.cmd spring-boot:run
```

Si un request público responde `503`, comprobá que su servicio destino esté escuchando en el puerto configurado y, para una compra, que estén disponibles también las dependencias directas de MS3. Un `401` indica que falta un JWT válido; un `403`, que el rol no está autorizado. Un `404` para una ruta interna o no publicada es esperado. Postman no aplica CORS: ese comportamiento se verifica con la suite automatizada o desde un navegador.

## Configuración

| Variable | Valor predeterminado | Descripción |
|---|---|---|
| `SERVER_PORT` | `8086` | Puerto del Gateway. |
| `MS1_URL` | `http://localhost:8081` | Eventos y Lugares. |
| `MS2_URL` | `http://localhost:8080` | Disponibilidad y Reservas. |
| `MS3_URL` | `http://localhost:8083` | Compras y Devoluciones. |
| `MS5_URL` | `http://localhost:8085` | Entradas. |
| `MS6_URL` | `http://localhost:8087` | Usuarios y autenticación. |
| `JWT_ISSUER` | `ticketya-usuarios` | Emisor (`iss`) exigido en los JWT; debe coincidir con el de MS6. |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Orígenes web permitidos para CORS, separados por coma. |
| `JWT_SECRET` | Obligatoria | Clave compartida con el emisor; JWT HS256; mínimo 32 bytes UTF-8. No se incluye una clave predeterminada. |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `2000` | Tiempo máximo para conectar con el servicio destino. |
| `GATEWAY_RESPONSE_TIMEOUT` | `5s` | Tiempo máximo de respuesta del servicio destino. |

## JWT y roles

El Gateway funciona como resource server: **no emite tokens**. Los emite MS6 (`POST /auth/login`), firmados con HS256 y la misma `JWT_SECRET`. El Gateway valida firma, emisor (`iss` = `JWT_ISSUER`) y vigencia (`exp`), y exige `sub` (ID del usuario) y `roles`: una lista no vacía con valores entre `CLIENTE`, `ORGANIZADOR`, `ADMIN` y `PERSONAL_ACCESO`. Un usuario puede tener varios roles. La clave se debe inyectar desde un gestor de secretos/entorno, nunca versionar.

Sin token, una ruta protegida responde `401 NO_AUTENTICADO`. Un token vencido, con firma inválida, de otro emisor o mal formado responde `401 TOKEN_INVALIDO`, y el pedido no se reenvía. Un rol insuficiente responde `403 ACCESO_DENEGADO`.

| Operación | Roles admitidos |
|---|---|
| Registrarse e iniciar sesión (`POST /auth/registro`, `POST /auth/login`) | Pública (sin token) |
| Consultar eventos (`GET /eventos`, `GET /eventos/{id}`) | Pública (sin token) |
| Consultar y editar el perfil propio (`/usuarios/me`) | Cualquier rol |
| Listar/consultar usuarios, asignar roles, activar/desactivar | `ADMIN` |
| Consultar lugares | `CLIENTE`, `ORGANIZADOR`, `ADMIN`, `PERSONAL_ACCESO` |
| Crear lugares/eventos/sectores, configurar y publicar eventos | `ORGANIZADOR`, `ADMIN` |
| Suspender, reactivar o cancelar eventos | `ADMIN` |
| Reservar, comprar, pagar y solicitar devoluciones | `CLIENTE` |
| Consultar compras/devoluciones | `CLIENTE`, `ADMIN` |
| Aprobar/rechazar devoluciones y operar reintegros | `ADMIN` |
| Consultar entradas | `CLIENTE`, `ADMIN` |
| Validar el QR de una entrada (`POST /eventos/{id}/ingresos`) | `PERSONAL_ACCESO`, `ADMIN` (MS5 verifica además la asignación al evento) |

El Gateway elimina siempre (también en rutas públicas) cualquier `X-User-Id`, `X-User-Roles` y `X-User-Role` entrante. Con un token válido los reconstruye desde los claims verificados: `X-User-Id` = `sub` y `X-User-Roles` = todos los roles separados por coma (ordenados alfabéticamente). Por compatibilidad con la Entrega 1, `X-User-Role` se sigue enviando **solo** cuando el usuario tiene un único rol; con varios roles no se envía, para no elegir uno arbitrariamente. Los servicios deben migrar a `X-User-Roles`.

Los servicios mantienen la autorización de negocio y los controles de titularidad; el Gateway no verifica propiedad de recursos. Las rutas desconocidas, métodos no permitidos y rutas internas siguen sin reenviarse (404).

## Rutas públicas

El Gateway define una lista explícita de paths y métodos permitidos y reenvía los paths sin modificarlos:

| Servicio | Operaciones públicas |
|---|---|
| Eventos/Lugares (MS1) | `GET, POST /lugares`; `GET /lugares/{id}`; `GET, POST /lugares/{id}/sectores`; `GET, POST /eventos`; `GET /eventos/{eventoId}`; `PUT /eventos/{eventoId}/sectores/{sectorId}/precio`; `PUT /eventos/{eventoId}/limite-entradas-por-cliente`; `PUT /eventos/{eventoId}/publicar`; `PUT /eventos/{eventoId}/venta/suspender`; `PUT /eventos/{eventoId}/venta/reactivar`; `PUT /eventos/{eventoId}/cancelar`. |
| Disponibilidad/Reservas (MS2) | `POST /disponibilidad/eventos/{eventoId}/reservas`. |
| Compras/Devoluciones (MS3) | `GET, POST /compras`; `GET /compras/{compraId}`; `POST /compras/{compraId}/pago`; `GET, POST /devoluciones`; `GET /devoluciones/{devolucionId}`; `POST /devoluciones/{devolucionId}/aprobacion`; `POST /devoluciones/{devolucionId}/rechazo`; `GET /reintegros`; `POST /reintegros/{reintegroId}/reintento`. |
| Entradas (MS5) | `GET /entradas`; `GET /entradas/{id}`; `POST /eventos/{eventoId}/ingresos`. |
| Usuarios (MS6) | `POST /auth/registro`; `POST /auth/login`; `GET, PUT /usuarios/me`; `GET /usuarios`; `GET /usuarios/{id}`; `PUT /usuarios/{id}/roles`; `PUT /usuarios/{id}/estado`. |

No se enrutan `/internal/**`, consultas/confirmaciones/liberaciones internas de reservas, el callback `PUT /eventos/{eventoId}/disponibilidad`, `POST /eventos-cancelados`, emisión interna de entradas ni endpoints de Pagos. Los paths desconocidos y métodos no permitidos no se reenvían.

## Trazabilidad y despliegue

Cada solicitud recibe o genera un `X-Correlation-Id` UUID (identificador de correlación acordado por el grupo), se devuelve al cliente y se propaga al servicio destino. El log de consola registra ID, método, path, usuario, roles, status y duración; no registra query strings ni credenciales. Las escrituras no se reintentan automáticamente para evitar duplicar operaciones. Ante desconexión o timeout, el Gateway responde HTTP 503 en el formato de error existente.

Para construir la imagen desde esta carpeta: `docker build -t ticketya/api-gateway .`. El contenedor requiere la clave JWT y las URLs destino por variables de entorno. Este Dockerfile no reemplaza el `docker-compose.yml` de la plataforma, que queda fuera del alcance de este cambio.
