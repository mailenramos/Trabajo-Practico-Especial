# Proposal

## Why

MS8 ya conserva los intereses de cada cliente, pero todavía no los convierte en una lista útil de eventos próximos. Este cambio agrega recomendaciones explicables y acotadas que combinan esos intereses con el catálogo vigente de MS1 y, cuando Compras está disponible, señales del historial y de co-compras.

## What Changes

- Agregar `GET /recomendaciones/me`, protegido con la identidad por `X-User-Id` y `X-User-Roles` y el rol `CLIENTE`.
- Obtener todas las páginas de eventos publicados o agotados de MS1; si MS1 falla, responder `503`.
- Puntuar eventos futuros según coincidencias de artistas, tipos y ciudades preferidos, tipos del historial de compras y co-compras consultadas para no más de cinco eventos comprados.
- Tolerar fallos de Compras usando únicamente las preferencias cuando sus consultas no se completen; devolver solo resultados con puntuación positiva, ordenados por puntuación, fecha e ID, con un máximo de 20.
- Configurar URLs de MS1 y Compras, `SERVICIO_TOKEN` para `X-Service-Token` y timeouts HTTP; actualizar Compose, README y Postman.
- Agregar pruebas MVC, unitarias y de clientes HTTP usando `MockRestServiceServer`.
- Mantener fuera de alcance caché, circuit breakers, avisos de publicación, endpoint interno de MS8, paginación de recomendaciones, exclusión de eventos comprados e interfaz.

## Capabilities

### New Capabilities

- `recomendaciones-personalizadas`: consulta de recomendaciones futuras del cliente mediante puntuación de preferencias y señales opcionales de compras.

### Modified Capabilities

Ninguna. La capacidad existente `preferencias-cliente` no cambia su contrato.

## Impact

- Afecta únicamente `Backend/mcsv-preferencias-recomendaciones`: nuevo endpoint, integración HTTP con MS1 y Compras, configuración y pruebas.
- Requiere que MS1 exponga el catálogo paginado en `/internal/eventos-publicados` y que Compras acepte el header `X-Service-Token` en las consultas de historial y co-compras.
- No modifica los demás microservicios ni introduce nuevas dependencias de infraestructura.
