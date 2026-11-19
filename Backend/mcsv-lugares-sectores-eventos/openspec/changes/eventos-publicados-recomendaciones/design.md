# Design

## Contexto actual

- `EventoInternoController` ofrece `GET /internal/eventos/{eventoId}` sin headers de usuario. Las rutas `/internal/...` son llamadas directas entre servicios y no se publican en el Gateway.
- `Evento` ya persiste `tipo`, `participantes`, `fechaHora`, `estado` y `ciudad`; la ciudad es la copia tomada del lugar al crear el evento.
- La búsqueda existente usa paginación `page` cero-based y una respuesta con `contenido`, `pagina`, `tamano`, `totalElementos` y `totalPaginas`.
- La publicación confirma primero la inicialización de disponibilidad en Reservas. La outbox usa `MongoTransactionManager`, IDs estables y `OutboxDispatcher`, que agrega `X-Aviso-Id` y administra los estados y reintentos.
- El requisito vigente `Publicar eventos con disponibilidad inicializada` exige confirmación de Reservas antes de cambiar el estado local; el aviso post-confirmación complementa esa regla y no la contradice.

## Consulta interna para MS8

Agregar `GET /internal/eventos-publicados` como una nueva ruta interna, independiente del detalle de Compras. Consultar MongoDB con estado en `publicado` o `agotado`, sin filtrar por fecha. MS8 conserva la responsabilidad de excluir eventos finalizados, cancelados o cuya fecha ya pasó.

Cada elemento devuelve únicamente `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad`, `fechaHora` y `estado`. `ciudad` reutiliza la copia del lugar almacenada en `Evento`; `fechaHora` se serializa desde `Instant` en ISO-8601 UTC. Ordenar por `fechaHora` ascendente y, en caso de empate, por ID del evento ascendente para que la paginación sea estable. Mantener el formato de respuesta paginada existente. `page` inicia en cero, `size` usa default `100` y su máximo es `200`; `page` negativo, `size` menor que 1 o mayor que 200 responden `400 Bad Request` en el formato de error estándar. Las consultas no requieren identidad de usuario y permanecen fuera del Gateway.

## Aviso de publicación

Conservar el orden actual: Reservas confirma primero la inicialización; luego una transacción Mongo local guarda el evento publicado y un aviso outbox. El aviso contiene:

| Campo | Valor |
|---|---|
| ID estable | `publicacion-{eventoId}` |
| Tipo | `PUBLICACION` |
| Destino | `POST {PREFERENCIAS_BASE_URL}/internal/preferencias/eventos-publicados` |
| Payload | `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad`, `fechaHora` |

La operación transaccional reutiliza el `MongoTransactionManager` y la colección `outbox` existentes. Después del commit, entregar mediante el mismo `OutboxDispatcher` y el mismo mecanismo de claim que ya usa el aviso de cancelación. No se modifica la clasificación de respuestas, los reintentos, los estados ni el header `X-Aviso-Id`. La llamada HTTP ocurre fuera de la transacción. Si Preferencias falla, el aviso queda sujeto a la política existente y la publicación conserva su respuesta exitosa.

Si una escritura local falla, Mongo revierte conjuntamente el cambio de estado y el aviso. La llamada anterior a Reservas no forma parte de esa transacción distribuida; al reintentar la publicación, la inicialización idempotente existente permite continuar.

### Prueba de independencia del tipo de aviso

`OutboxDispatcher` y `RestClientOutboxHttpClient` de producción no ramifican por `tipo`: toman el ID, destino y payload de cada `AvisoOutbox`, y el cliente envía el ID en `X-Aviso-Id`. Las pruebas genéricas usan ahora IDs, destinos y payloads neutrales; las pruebas propias del flujo de cancelación conservan sus IDs y endpoints. La prueba de punta a punta para `PUBLICACION` persiste el aviso, lo entrega por el dispatcher a un servidor HTTP de prueba y verifica el método, destino, payload, `X-Aviso-Id`, reintento ante `503`, éxito tras el reintento y `ERROR_FINAL` ante `4xx`. No se requieren cambios al dispatcher ni al cliente HTTP para agregar este tipo.

## Configuración

- En `application.yml`, `PREFERENCIAS_BASE_URL` tiene default para host `http://localhost:8089`.
- En Compose, MS1 recibe `PREFERENCIAS_BASE_URL` con default `http://preferencias:8089`.
- Agregar la variable a la tabla del README. No agregar configuración de cache, gestión manual de avisos ni variables nuevas para el dispatcher.

## Pruebas y alcance

Cubrir la consulta sin identidad, los estados y campos del resultado, orden estable, paginación por defecto, tamaño máximo y parámetros inválidos; atomicidad del cambio/aviso con Mongo replica set de Testcontainers; y publicación exitosa cuando Preferencias no está disponible. Añadir la prueba punta a punta descrita arriba para `PUBLICACION`.

No agregar filtros por fecha ni estado de venta; no cambiar el dispatcher general ni introducir un cliente de entrega paralelo.
