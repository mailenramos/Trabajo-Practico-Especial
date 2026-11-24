# Proposal

## Why

La propuesta de la Entrega 2 (secciones 2.3 y 6.2.3) asigna a Compras y devoluciones los reportes comerciales de cada evento para sus organizadores, sin crear un microservicio de reportes. Hoy los organizadores no tienen forma de ver ventas, devoluciones, recaudación ni ocupación de sus eventos, y Compras es el único servicio que tiene los precios históricos, las devoluciones y los reintegros.

## What Changes

- Cuatro endpoints nuevos de solo lectura, calculados al momento con consultas agregadas (sin guardar resultados precalculados):
  - `GET /reportes/eventos/{eventoId}/ventas`: compras confirmadas, entradas vendidas e importes históricos, por sector y total.
  - `GET /reportes/eventos/{eventoId}/devoluciones`: entradas devueltas (por devolución del cliente y por cancelación del evento, separadas por origen), entradas netas e importes asociados; las devoluciones en curso aparte.
  - `GET /reportes/eventos/{eventoId}/recaudacion`: recaudación bruta, reintegros exitosos y recaudación neta, por sector y total; reintegros pendientes de reconciliación y cobros de compras no confirmadas aparte.
  - `GET /reportes/eventos/{eventoId}/ocupacion`: capacidad, lugares vendidos y reservas temporales según Disponibilidad y reservas, y porcentaje de ocupación por sector y total.
- Acceso: ADMIN a cualquier evento; ORGANIZADOR solo a los eventos cuyo `organizadorId` (según Eventos) es su `X-User-Id`. Organizador ajeno → 403 `EVENTO_AJENO`; otro rol → 403; evento inexistente → 404 `EVENTO_NO_ENCONTRADO`.
- Todos los reportes indican si hay operaciones pendientes (`hayOperacionesPendientes`).
- El cliente de Eventos pasa a leer `organizadorId` de `GET /internal/eventos/{eventoId}` (campo que MS1 ya devuelve).
- El cliente de Reservas suma la consulta `GET /disponibilidad/eventos/{eventoId}` (MS2 ya devuelve `cantidadReservada` y `cantidadVendida` por sector).
- OpenAPI y colección Postman de MS3 actualizados.
- Se deja preparado (como documento, sin aplicarlo) el fragmento para el API Gateway: ruta `ms3-reportes` y matchers de seguridad.

## Capabilities

### New Capabilities
- `reportes-organizadores`: reportes de ventas, devoluciones, recaudación y ocupación de un evento para su organizador o un administrador, con las reglas de cálculo de cada métrica y el control de titularidad.

### Modified Capabilities
<!-- Ninguna: identidad-roles ya define X-User-Id/X-User-Roles y el rol administrador; los reportes no cambian el comportamiento de compras, devoluciones ni reintegros. -->

## Impact

- **Código (MS3, `Backend/mcsv-compras-devoluciones`)**: nuevo `ReporteController`, `ReporteService` y DTOs de respuesta; consultas agregadas nuevas en `CompraRepository`, `SolicitudDevolucionRepository`, `ReintegroRepository` y `EntradaDeReintegroRepository`; `EventosCliente.Evento` con `organizadorId`; `ReservasCliente` con la consulta de disponibilidad; `Permisos` con el rol organizador; dobles de prueba actualizados.
- **API**: 4 endpoints públicos nuevos (vía Gateway); ninguno existente cambia.
- **Servicios externos de los que depende**:
  - Gestión de lugares, sectores y eventos (MS1): `GET /internal/eventos/{eventoId}` para titularidad, nombre, estado y sectores. Si no responde, los cuatro reportes responden 503.
  - Gestión de disponibilidad y reservas (MS2): `GET /disponibilidad/eventos/{eventoId}`, solo para ocupación. Si no responde, ocupación responde 503 `SERVICIO_NO_DISPONIBLE`.
  - API Gateway: necesita la ruta y los permisos nuevos (los aplica su dueño con el fragmento entregado).
- **Base de datos**: sin tablas ni columnas nuevas (solo lecturas).

## Fuera de alcance

- Modificar el API Gateway (solo se entrega el fragmento).
- Cambios en MS1 o MS2: ya exponen `organizadorId`, `cantidadVendida` y `cantidadReservada`.
- Compras gratuitas de punta a punta (confirmación sin cobro, devolución sin reintegro): no las implementa este cambio. Si existen entradas con precio 0, los reportes las cuentan en cantidades y ocupación con importe 0.
- Reportes de varios eventos a la vez, filtros por fecha, exportación (CSV/PDF), paginación y caché de reportes.
- Consultar a Pagos: los importes reintegrados salen de los reintegros que Compras ya registra y coordina.
