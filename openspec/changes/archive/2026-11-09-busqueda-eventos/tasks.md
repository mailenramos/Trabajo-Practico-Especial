# Tasks

## A. Trabajo independiente de otros servicios

- [x] A.1 Crear los índices MongoDB requeridos para búsqueda: `{estado, fechaHora}`, `{estado, ciudad, fechaHora}`, `{tipo}`, `{organizadorId}`, `{participantes}` multikey y texto sobre nombre, descripción y participantes; verificar creación y consultas con Testcontainers Mongo.
- [x] A.2 Implementar DTOs y validación de parámetros de `GET /eventos`: filtros, ISO-8601 UTC inclusivo, límites de página, orden, dirección y `q`; verificar valores por defecto y respuestas `400` con formato estándar.
- [x] A.3 Implementar búsqueda dinámica con `MongoTemplate`, `Criteria` y `Pageable`; combinar filtros con AND, restringir siempre a publicado/agotado y ejecutar filtros, orden, paginación y conteo en MongoDB; probar filtros aislados/combinados, cero resultados y exclusión de otros estados.
- [x] A.4 Implementar orden por fecha, `precioMinimo` y relevancia con búsqueda textual y desempate por fecha; verificar fechas ascendente, precios ascendente/descendente, `q`, relevancia sin `q` y orden inválido.
- [x] A.5 Implementar `GET /eventos` con identidad requerida y roles CLIENTE/ORGANIZADOR/ADMIN; verificar `200`, `401` y `403` con pruebas de API.
- [x] A.6 Implementar `GET /eventos/{eventoId}` sin dependencia de Reservas: consulta local de evento y lugar, respuesta con los campos del evento, sectores, precios y disponibilidad nula; aplicar visibilidad de borradores por dueño/admin; verificar `200` y `404` para inexistente y borrador ajeno.
- [x] A.7 Verificar autorización del detalle para los tres roles, ausencia de headers (`401`), rol no admitido (`403`), y visibilidad de publicado, agotado, cancelado y finalizado con pruebas de API.
- [x] A.8 Documentar ambos endpoints en OpenAPI/Swagger con parámetros, headers, códigos HTTP, paginación, filtros y ejemplos de respuesta; actualizar el contrato de rutas públicas del API Gateway manteniendo fuera la ruta interna `/internal/eventos/{eventoId}`.
- [x] A.9 Completar pruebas unitarias, de repositorio Mongo/Testcontainers y de API para todos los escenarios del spec de búsqueda, orden, paginación y detalle sin integración; ejecutar `mvn test`.

## B. Integración de disponibilidad de Reservas en el detalle

- [x] B.1 Extender la interfaz existente `ReservasClient` con `GET /disponibilidad/eventos/{eventoId}` y el DTO de respuesta por sector, conservando base URL y timeouts existentes; verificar ruta, deserialización y manejo de respuestas con cliente simulado.
- [x] B.2 Consultar disponibilidad solo para eventos publicados o agotados y combinar los sectores por `sectorId`, incluidos cantidad disponible y asientos numerados; verificar orden distinto de sectores, sectores faltantes y forma de la respuesta con cliente simulado.
- [x] B.3 Ante timeout, error de transporte o respuesta `404`/`500` de Reservas, devolver el detalle con `disponibilidad: null` sin propagar el error; verificar fallos mediante cliente simulado y prueba de API.
- [x] B.4 Verificar con cliente simulado que borradores visibles, cancelados y finalizados no llaman a Reservas y devuelven disponibilidad nula; ejecutar pruebas de integración del detalle completo y reejecutar la suite.
