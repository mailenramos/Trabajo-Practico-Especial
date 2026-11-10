# Tasks

## 1. Rutas configurables en el cliente de Pagos

- [x] 1.1 Crear el record `ticketya.cliente.rest.RutasPagos` (`cobro`, `pagosPorCompra`, `reintegro`, `reintentoReintegro`, `consultaReintegro`) con `@ConfigurationProperties("servicios.pagos.rutas")`, las rutas internas como valores por defecto y validación (no vacía, empieza con `/`); habilitarlo con `@EnableConfigurationProperties`. Agregar las cinco propiedades a `application.properties` con las rutas internas. Verificar con una prueba unitaria del record (valores por defecto y ruta inválida rechazada) y con `.\mvnw test-compile`.
- [x] 1.2 Hacer que `PagosClienteRest` reciba `RutasPagos` en el constructor y use sus rutas en las cinco llamadas, sin cambiar encabezados, cuerpos ni manejo de errores. Actualizar el javadoc con las rutas internas. Verificar que `ComprasApplicationTests` levanta el contexto.

## 2. Pruebas del cliente

- [x] 2.1 En `PagosClienteRestTest`, registrar método, ruta, `Idempotency-Key` y cuerpo de cada pedido, construir el cliente con las rutas por defecto y cambiar la prueba de consulta por compra a `/internal/pagos/compra/compra-1`. Verificar con la prueba.
- [x] 2.2 Agregar pruebas de `cobrar` (POST `/internal/pagos`, clave y cuerpo; 409 lanza `ConflictoEnPagosException`), `reintegrar` (POST `/internal/pagos/p-1/reintegros`, clave y cuerpo con `refundRequestId`, `amount`, `reason`), `reintentarReintegro` (POST `/internal/reintegros/r-1/reintentar`, sin cuerpo ni clave; 409 lanza `ConflictoEnPagosException`) y `consultarReintegro` (GET `/internal/reintegros/r-1`; 404 da vacío). Verificar con la prueba.
- [x] 2.3 Agregar una prueba con rutas configuradas (por ejemplo, las viejas `/api/v1/...`) que verifique que el cliente llama exactamente a esas. Verificar con la prueba.
- [x] 2.4 Actualizar el comentario de `PagosFalso` que nombra `/retry`. Verificar que no queda `retry` referido a Pagos en `src/test` (`grep`).

## 3. Comentarios y documentación

- [x] 3.1 Actualizar los comentarios de `Compra`, `Reintegro`, `ReintegroService`, `DevolucionService` y `CancelacionService` que nombran `/payments/.../refunds` o `/retry` de Pagos. Verificar con `grep` que no queda `api/v1`, `/refunds` ni `retry` referido a Pagos en `src/main/java`.
- [x] 3.2 En `openapi.yaml`, cambiar las rutas de Pagos del reintento de reintegro y de la cancelación de eventos por las internas. Verificar con `DocumentacionOpenApiTest` y `grep`.
- [x] 3.3 En `docs/historias-ms3.md`: rutas internas en la sección de Pagos (incluido `GET /internal/pagos/{paymentId}`), "reintentar" en vez de `retry`, y un punto abierto para confirmar con Pagos que cuerpos, códigos y formato de error no cambian. Verificar releyendo la sección y con `grep`.
- [x] 3.4 En el README, documentar las propiedades `servicios.pagos.rutas.*` (valor por defecto y que cada ruta conserva su variable). Verificar releyendo la tabla de propiedades.
- [x] 3.5 En `docs/diagramas-ms3.md` y `docs/diagramas-ms3/secuencia-compra.mmd`, cambiar `POST /api/v1/payments` y `POST /payments/{pagoId}/refunds` por las rutas internas. Verificar con `grep`.

## 4. Verificación integral

- [x] 4.1 Correr `.\mvnw test` en `Backend/mcsv-compras-devoluciones` y confirmar que pasa toda la suite.
- [x] 4.2 Buscar en todo el repositorio, sin contar los cambios archivados, `api/v1/payments`, `api/v1/refunds` y `retry` referido a Pagos, y confirmar que no queda ninguno (salvo la prueba de rutas configuradas). Correr `openspec validate rutas-internas-pagos --strict`.

## Workflow follow-up

- Confirmar con Pagos que cuerpos, códigos de respuesta y formato de error no cambian con las rutas internas.
- Regenerar `docs/diagramas-ms3/secuencia-compra.png` desde el `.mmd`.
- Archivar el cambio cuando esté revisado.
