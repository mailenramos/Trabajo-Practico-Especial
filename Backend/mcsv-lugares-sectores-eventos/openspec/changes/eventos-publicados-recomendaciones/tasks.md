# Tasks

- [x] 1. Configurar `PREFERENCIAS_BASE_URL`: default `http://localhost:8089` en `application.yml`, default Docker `http://preferencias:8089` en Compose y fila correspondiente en el README.
- [x] 2. Implementar `GET /internal/eventos-publicados` con filtro de estado, proyección de campos y formato de paginación existente; agregar cobertura para acceso sin identidad y respuesta vacía.
- [x] 3. Ordenar por `fechaHora` ascendente e ID ascendente, y agregar `page` cero-based y `size` default 100, máximo 200; probar orden estable, metadatos y `400` para `page` negativo o `size` fuera de 1–200.
- [x] 4. Registrar el aviso `PUBLICACION` y el cambio a `publicado` en una transacción tras la confirmación de Reservas; probar ID, destino, payload, rollback atómico y ausencia de aviso si falla Reservas.
- [x] 5. Probar de punta a punta un aviso `PUBLICACION` persistido y entregado por `OutboxDispatcher`: comprobar ID `publicacion-{eventoId}`, destino, payload, header `X-Aviso-Id`, reintento ante `503` y `ERROR_FINAL` ante `4xx`. Neutralizar únicamente los fixtures de pruebas genéricas que presuponen `cancelacion-*`/Compras; conservar las pruebas específicas de cancelación.
- [x] 6. Actualizar la especificación existente de `eventos`, `docs/modelo-de-datos.md`, `docs/diseno.md`, `docs/api-gateway-contract.md` y agregar un request `GET /internal/eventos-publicados` a `postman/TicketYa - Eventos.postman_collection.json`; ejecutar las pruebas relevantes y validar el change con OpenSpec estricto.
