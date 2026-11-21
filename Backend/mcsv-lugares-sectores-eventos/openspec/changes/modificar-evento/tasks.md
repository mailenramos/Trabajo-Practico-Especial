# Tareas

## 1. Modificación local y API

- [x] 1.1 Agregar request y `PUT /eventos/{eventoId}` con validación y autorización específica para titular o ADMIN; verificar con pruebas de identidad ausente, roles múltiples, otro organizador, ADMIN, body vacío, fecha inválida o pasada, lugar inexistente y evento inexistente.
- [x] 1.2 Implementar guardas para eventos cancelados, finalizados y con intención de cancelación pendiente; verificar cada `409` y el control optimista en concurrencia con la intención.
- [x] 1.3 Persistir cambios reales de fecha, lugar y copia de ciudad, conservando sectores y disponibilidad; probar campos individuales, valores idénticos sin escritura ni incremento, borrador y consultas pública e interna.
- [ ] 1.4 Documentar endpoint, códigos y conservación de sectores en README, OpenAPI, Postman y `docs/api-gateway-contract.md`; verificar que los ejemplos y la colección reflejen el contrato.

## 2. Aviso y transacción

- [x] 2.1 Crear el aviso de modificación con ID y versión posteriores a la escritura, payload completo y destino de Compras; probar fecha sola, lugar solo, ambos, estado agotado y ausencia de aviso en borrador o sin cambios.
- [x] 2.2 Guardar cambio y aviso en una transacción MongoDB e invalidar con `CatalogoCacheInvalidador` tras el commit; verificar con Testcontainers rollback ante fallo del aviso y que una búsqueda cacheada refleje los datos nuevos.
- [x] 2.3 Antes de corregir nada, listar las rutas de escritura de eventos que no incrementan la versión, incluido el backfill; modificar solo las necesarias, probar con Testcontainers versiones crecientes e IDs de aviso distintos tras escrituras sucesivas y correr toda la suite.
- [ ] 2.4 Reutilizar la entrega outbox fuera de la transacción; probar `200` y `202`, `400` y `403`, `5xx`, timeout y conexión, headers, payload estable, 8 reclamos y backoff de 30 s duplicado hasta 15 min.
- [ ] 2.5 Documentar en README, OpenAPI y Postman el aviso completo, configuración, reintentos, fallos definitivos y limitaciones de caché y sectores; verificar coherencia con el contrato de Compras.

## 3. Integración

- [ ] 3.1 Documentar el nuevo PUT /eventos/{eventoId} en docs/api-gateway-contract.md (ruta, método, headers de identidad) y dejar constancia de que su habilitación en el Gateway queda pendiente de confirmar con su responsable.
- [ ] 3.2 Ejecutar las pruebas relevantes con MongoDB de Testcontainers, nunca MongoDB local, y validar el cambio con `openspec validate modificar-evento --strict` y `git diff --check`.
