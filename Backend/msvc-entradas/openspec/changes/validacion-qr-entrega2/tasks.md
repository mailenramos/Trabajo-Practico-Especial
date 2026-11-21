# Tasks

- [x] 1. Migración V4 (UTILIZADA, índice, `control_eventos`, `ingresos`) y entidades.
- [x] 2. Cierre del evento con bloqueo exclusivo y emisión con bloqueo compartido + `EVENTO_CERRADO`. Tests unitarios.
- [x] 3. `ENTRADA_UTILIZADA` al bloquear por devolución. Tests unitarios.
- [x] 4. Cliente de autorización de MS1 (timeouts, token, correlación) y validación QR. Tests unitarios y del cliente.
- [x] 5. Consulta por compra, roles en consultas públicas, quitar `println`. Tests.
- [x] 6. Filtro de `X-Service-Token` en `/internal/**` y de `X-Correlation-Id`. Tests HTTP.
- [x] 7. Ruta de validación en el Gateway y `GET /entradas` para `CLIENTE`/`ADMIN`. Tests.
- [x] 8. Tests de concurrencia con PostgreSQL (`IngresoConcurrencyIntegrationTest`, 6 tests): ejecutados con Docker el 2026-11-20, sin fallos.
- [x] 9. Actualizar README, `openapi-entradas.yaml`, specs, Postman y US.
