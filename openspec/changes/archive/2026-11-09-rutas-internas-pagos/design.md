## Context

Ver proposal.md (Why). Estado actual observado en el código:

- `PagosClienteRest` tiene las cinco rutas fijas como literales en cada llamada (`/api/v1/payments`, `/api/v1/payments/purchase/{id}`, `/api/v1/payments/{id}/refunds`, `/api/v1/refunds/{id}/retry`, `/api/v1/refunds/{id}`). Solo la URL base (`servicios.pagos.url`) es configurable.
- Los clientes REST reciben su configuración con `@Value` en el constructor; no hay ninguna clase `@ConfigurationProperties` en el proyecto.
- `PagosClienteRestTest` levanta un `HttpServer` local y construye el cliente a mano; hoy solo prueba la consulta por compra y verifica una ruta.
- `PagosFalso` implementa la interfaz `PagosCliente` en memoria: no conoce rutas, solo las nombra en un comentario (`/retry`).
- Fuera del cliente, las rutas viejas aparecen en comentarios (`Compra`, `Reintegro`, `ReintegroService`, `DevolucionService`, `CancelacionService`), en `openapi.yaml` (reintento de reintegro y cancelación de eventos), en `docs/historias-ms3.md` (contrato de Pagos) y en los dos diagramas de secuencia. Las specs vigentes no nombran rutas de Pagos.

## Goals / Non-Goals

**Goals:**
- Llamar a Pagos por sus rutas internas, sin cambiar qué se envía ni cómo se interpretan las respuestas.
- Poder cambiar cualquiera de las rutas por configuración, sin recompilar.
- Que las pruebas fallen si el cliente vuelve a llamar a una ruta que no es la configurada.

**Non-Goals:**
- Cambiar la interfaz `PagosCliente`, los records `Pago` y `Reintegro` o el manejo de errores.
- Usar `GET /internal/pagos/{paymentId}`.
- Hacer configurables las rutas de los otros clientes.

## Contrato con Pagos (MS4) — rutas internas

| Operación | Ruta nueva | Ruta anterior | Propiedad |
| --- | --- | --- | --- |
| Cobro (con `Idempotency-Key`) | `POST /internal/pagos` | `POST /api/v1/payments` | `servicios.pagos.rutas.cobro` |
| Pagos de una compra (lista; 200 `[]` si no hay) | `GET /internal/pagos/compra/{purchaseId}` | `GET /api/v1/payments/purchase/{id}` | `servicios.pagos.rutas.pagos-por-compra` |
| Reintegro (con `Idempotency-Key`) | `POST /internal/pagos/{paymentId}/reintegros` | `POST /api/v1/payments/{id}/refunds` | `servicios.pagos.rutas.reintegro` |
| Reintento de reintegro (sin cuerpo ni clave) | `POST /internal/reintegros/{refundId}/reintentar` | `POST /api/v1/refunds/{id}/retry` | `servicios.pagos.rutas.reintento-reintegro` |
| Consulta de reintegro | `GET /internal/reintegros/{refundId}` | `GET /api/v1/refunds/{id}` | `servicios.pagos.rutas.consulta-reintegro` |
| Consulta de un pago (no se usa) | `GET /internal/pagos/{paymentId}` | `GET /api/v1/payments/{paymentId}` | — |

**Contrato a acordar:** se asume que los cuerpos, los códigos de respuesta (201/200, 409, 404, 400) y el formato de error no cambian con las rutas. Queda por confirmar con Pagos. No se inventa ninguna ruta fuera de la tabla.

## Decisions

1. **Un record `RutasPagos` con `@ConfigurationProperties("servicios.pagos.rutas")`** con los cinco campos y las rutas internas como valores por defecto (por ejemplo, con `@DefaultValue`), habilitado con `@EnableConfigurationProperties`. `PagosClienteRest` lo recibe en el constructor junto a la URL y los tiempos de espera. Ventajas: las cinco rutas viajan juntas, las pruebas construyen el cliente con `RutasPagos` (las internas por defecto o unas propias) y no se suman cinco `@Value` sueltos al constructor.
   Alternativa descartada: cinco `@Value("${servicios.pagos.rutas.x:/internal/...}")`. Sigue el estilo de los otros clientes, pero repite cada valor por defecto en dos lugares (anotación y `application.properties`) y alarga el constructor.

2. **Las rutas son plantillas de URI con variables** (`{purchaseId}`, `{paymentId}`, `{refundId}`). `RestClient.uri(plantilla, valores...)` las expande por posición, así que el nombre de la variable no importa: una ruta configurada debe tener exactamente una variable donde hoy hay una. El record valida al construirse que ninguna ruta esté vacía y que empiece con `/`; un valor mal escrito hace fallar el arranque, no la primera llamada.

3. **`application.properties` lista las cinco propiedades con las rutas internas**, aunque coincidan con los valores por defecto, para que se vea qué se puede cambiar. Volver a las rutas viejas es solo configuración.

4. **Nada más cambia en el cliente:** mismos encabezados, mismos cuerpos, mismo manejo de 409 (`ConflictoEnPagosException`) y 404 (vacío en la consulta de reintegro), y la consulta por compra sigue leyendo una lista.

5. **Pruebas del cliente con el `HttpServer` local**, registrando método, ruta, `Idempotency-Key` y cuerpo de cada pedido: una prueba por operación con las rutas por defecto, una con rutas configuradas (prefijo distinto) y una que verifica que el reintento no lleva cuerpo ni clave. `PagosFalso` no cambia de comportamiento porque trabaja sobre la interfaz.

6. **"retry" en comentarios y documentación:** donde se refiere a la operación de Pagos, pasa a "reintentar" con la ruta nueva (`/reintentar`). El nombre del método `reintentarReintegro` ya está en español y no cambia.

### Atomicidad e idempotencia

No cambian. El cobro sigue enviando la clave de cobro propia en `Idempotency-Key` y el reintegro su `refundRequestId` y su clave, registrados antes de llamar a Pagos; un reintento manda los mismos valores a la ruta nueva, así que Pagos sigue sin duplicar. El reintento de un reintegro fallido sigue siendo sobre el mismo `refundId`. Las transiciones de estado locales (compra, reintegro, cancelación) siguen siendo actualizaciones condicionales con bloqueo; el cambio solo toca la URL de cada llamada.

## Risks / Trade-offs

- [Pagos despliega las rutas internas en otro momento que este servicio] → las rutas viejas se pueden configurar con `servicios.pagos.rutas.*` mientras tanto, sin recompilar.
- [Pagos cambia también cuerpos o códigos] → queda como contrato a acordar; las pruebas del cliente fijan lo asumido y fallarían al ajustarlo.
- [Una ruta configurada con un número distinto de variables] → la expansión fallaría en la llamada; se documenta en el README que cada ruta conserva su única variable.
- [El `.png` del diagrama de secuencia queda con la ruta vieja] → se actualiza el `.mmd` y el bloque Mermaid de `diagramas-ms3.md`; la imagen se regenera aparte.

## Migration Plan

1. Desplegar con las rutas internas por defecto cuando Pagos las exponga.
2. Si Pagos todavía expone solo las viejas, configurar `servicios.pagos.rutas.*` con ellas (o con variables de entorno equivalentes) y quitarlas después.
3. Rollback: volver a las rutas viejas por configuración; no hay datos que migrar.
