# TicketYa API Gateway — Entrega 1

Gateway HTTP para las operaciones públicas de Eventos/Lugares, Disponibilidad/Reservas, Compras/Devoluciones y Entradas. Conserva los paths de los servicios, no agrega `/api/v1` y escucha en `http://localhost:8086` por defecto.

## Requisitos y ejecución

- JDK 17 o superior.
- Maven 3.9 o superior.

Desde esta carpeta:

```powershell
mvn spring-boot:run
```

Para ejecutar toda la suite automatizada (pruebas unitarias y de integración):

```powershell
mvn clean verify
```

Las pruebas usan servidores HTTP falsos y no requieren levantar MS1–MS5, MongoDB, MySQL ni Docker. Cubren el encaminamiento por servicio, preservación de la solicitud/respuesta, exclusión de rutas internas, identidad, CORS y fallos de conexión.

## Pruebas manuales con Postman

Importá estos dos archivos en Postman:

- `postman/TicketYa-Gateway.postman_collection.json`
- `postman/TicketYa-Gateway-Local.postman_environment.json`

Seleccioná el environment **TicketYa - Gateway local**. Por defecto, `gatewayUrl` es `http://localhost:8086`. La carpeta **01 - Consultas públicas** permite probar la búsqueda de eventos y el listado de lugares. La carpeta **02 - Identidad y allowlist** prueba que MS3 rechace una consulta sin identidad y que una ruta interna y un método no permitidos no respondan con éxito. Las carpetas **03 - Reserva** y **04 - Compra y entradas** requieren completar IDs y levantar los servicios/dependencias indicados en sus descripciones.

Para una reserva, completá `eventoId` con un evento publicado con disponibilidad y `sectorId` con un sector numerado libre; ajustá también `fila` y `numero`. El request guarda `reservaId` si MS2 lo devuelve. Para continuar, usá esa reserva al iniciar la compra; al crearla se guarda `compraId`. El pago usa una clave de idempotencia nueva por request. Ejecutá cada operación de escritura deliberadamente: crea datos y puede reservar lugares o procesar un pago.

Iniciá MongoDB/MySQL y los microservicios necesarios siguiendo el README de cada uno. Para consultar Eventos/Lugares, MS1 y MongoDB deben estar disponibles; para el flujo completo de compra y emisión también deben responder los servicios y bases de datos requeridos por MS2, MS3, MS4 y MS5. Finalmente iniciá el Gateway en esta carpeta:

```powershell
mvn spring-boot:run
```

Si un request público responde `503`, comprobá que su servicio destino esté escuchando en el puerto configurado y, para una compra, que estén disponibles también las dependencias directas de MS3. Un `404` para una ruta interna o no publicada es esperado. Postman no aplica CORS: ese comportamiento se verifica con la suite automatizada o desde un navegador.

## Configuración

| Variable | Valor predeterminado | Descripción |
|---|---|---|
| `SERVER_PORT` | `8086` | Puerto del Gateway. |
| `MS1_URL` | `http://localhost:8081` | Eventos y Lugares. |
| `MS2_URL` | `http://localhost:8080` | Disponibilidad y Reservas. |
| `MS3_URL` | `http://localhost:8083` | Compras y Devoluciones. |
| `MS5_URL` | `http://localhost:8085` | Entradas. |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Orígenes web permitidos para CORS, separados por coma. |

## Rutas públicas

El Gateway define una lista explícita de paths y métodos permitidos y reenvía los paths sin modificarlos:

| Servicio | Operaciones públicas |
|---|---|
| Eventos/Lugares (MS1) | `GET, POST /lugares`; `GET /lugares/{id}`; `GET, POST /lugares/{id}/sectores`; `GET, POST /eventos`; `GET /eventos/{eventoId}`; `PUT /eventos/{eventoId}/sectores/{sectorId}/precio`; `PUT /eventos/{eventoId}/limite-entradas-por-cliente`; `PUT /eventos/{eventoId}/publicar`; `PUT /eventos/{eventoId}/venta/suspender`; `PUT /eventos/{eventoId}/venta/reactivar`; `PUT /eventos/{eventoId}/cancelar`. |
| Disponibilidad/Reservas (MS2) | `POST /disponibilidad/eventos/{eventoId}/reservas`. |
| Compras/Devoluciones (MS3) | `GET, POST /compras`; `GET /compras/{compraId}`; `POST /compras/{compraId}/pago`; `GET, POST /devoluciones`; `GET /devoluciones/{devolucionId}`; `POST /devoluciones/{devolucionId}/aprobacion`; `POST /devoluciones/{devolucionId}/rechazo`; `GET /reintegros`; `POST /reintegros/{reintegroId}/reintento`. |
| Entradas (MS5) | `GET /entradas`; `GET /entradas/{id}`. |

No se enrutan `/internal/**`, consultas/confirmaciones/liberaciones internas de reservas, el callback `PUT /eventos/{eventoId}/disponibilidad`, `POST /eventos-cancelados`, emisión interna de entradas ni endpoints de Pagos. Los paths desconocidos y métodos no permitidos no se reenvían. Los permisos de rol y la validación de identidad siguen siendo responsabilidad de los microservicios.

## Identidad de Entrega 1

El Gateway propaga `X-User-Id` y `X-User-Role` cuando los recibe. No autentica, no crea una identidad predeterminada ni valida que el usuario sea quien declara ser. Esta limitación corresponde al esquema de identificación sin autenticación de la Entrega 1; no debe considerarse una configuración segura para producción.
