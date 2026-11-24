# Design

## Context

Ver `proposal.md` (Why) y `specs/reportes-organizadores/spec.md` (reglas de cada métrica). Estado actual relevante de MS3:

- `compras` (estado PENDIENTE/CONFIRMADA/FALLIDA, `pagoId`, `total`) y `detalles_compra` (`sectorId`, `sectorNombre`, `precioUnitario` histórico, `entradaId`). Una compra pasa a CONFIRMADA recién con las entradas emitidas; una PENDIENTE con `pagoId` es una compra cobrada con emisión incompleta.
- `solicitudes_devolucion` (estado, `entradaId`, `sectorId`, `monto`, `entradaDevuelta`). El reintegro de una devolución usa `refundRequestId = id de la solicitud`.
- `reintegros` (`compraId`, `refundRequestId`, `monto`, `origen` DEVOLUCION/CANCELACION_EVENTO/VENTA_RECHAZADA, estado) y `entradas_de_reintegro` (`reintegroId`, `entradaId`, `devuelta`). El monto de un reintegro por cancelación es exactamente la suma de los `precioUnitario` de sus entradas (`CancelacionService`), así que se puede repartir por sector sin diferencias.
- Identidad por `X-User-Id` / `X-User-Roles` (`UsuarioActualResolver`), rol administrador configurable en `Permisos`.
- Clientes REST con base común (`ClienteRest`): timeouts, traducción de fallas a `ServicioExternoException` (→ 503 `SERVICIO_NO_DISPONIBLE`) y propagación de `X-Correlation-Id`.

## Goals / Non-Goals

**Goals:**
- Calcular cada reporte con pocas consultas agregadas (GROUP BY sector), sin traer entidades completas a memoria.
- Que las cifras de un mismo reporte sean coherentes entre sí aunque haya compras o devoluciones concurrentes.
- Reutilizar los contratos que MS1 y MS2 ya exponen, sin pedirles cambios.

**Non-Goals:**
- Índices nuevos o tablas de resumen: el volumen del TP no lo justifica (se revisa si aparece en las pruebas de carga).
- Mostrar datos de compradores en los reportes (solo cantidades e importes).

## Decisions

### D1. Contratos con otros servicios (acordados, ya implementados en MS1 y MS2)

| Servicio | Ruta | Campos que se usan |
|---|---|---|
| Eventos (MS1) | `GET /internal/eventos/{eventoId}` (sin token) | `eventoId`, `nombre`, `estado`, `organizadorId`, `sectores[].sectorId`, `sectores[].nombre` |
| Disponibilidad y reservas (MS2) | `GET /disponibilidad/eventos/{eventoId}` (sin token) | `sectores[].sectorId`, `capacidadTotal`, `cantidadVendida`, `cantidadReservada` |

- MS1 404 → 404 `EVENTO_NO_ENCONTRADO`; sin respuesta o 5xx → 503 (comportamiento actual de `EventosClienteRest`).
- MS2 404 (evento sin inventario) → ocupación con `inventarioInicializado: false`; sin respuesta o 5xx → 503 `SERVICIO_NO_DISPONIBLE`.
- `organizadorId` se agrega como campo nuevo de `EventosCliente.Evento` (Jackson ya ignora desconocidos; un MS1 viejo sin el campo deja `null`, y entonces ningún organizador es dueño: solo ADMIN accede).
- `cantidadReservada` incluye reservas vencidas aún no liberadas (definición de MS2); se informa tal cual como "reservas temporales".
- Nada queda por acordar: no se inventan rutas.

### D2. Autorización antes de leer datos

Orden: (1) `UsuarioActual` (401 si falta `X-User-Id`); (2) si no tiene ADMIN ni ORGANIZADOR → 403 `SIN_PERMISO_REPORTES` sin llamar a MS1; (3) evento de MS1; (4) si no es ADMIN y `organizadorId` ≠ `X-User-Id` → 403 `EVENTO_AJENO`. El rol organizador se configura como el de administrador: `compras.rol-organizador` (por defecto `ORGANIZADOR`), en `Permisos`. Alternativa descartada: delegar la titularidad al Gateway; el Gateway no conoce al dueño del evento y la propuesta pide que cada servicio valide sus permisos.

### D3. Consultas agregadas en JPQL con proyecciones

Una consulta por bloque, siempre filtrando por `eventoId` (las tablas de reintegros se unen por `compraId`):
- Ventas: `detalles_compra ⋈ compras` con estado CONFIRMADA, `GROUP BY sectorId` → cantidad, suma de `precioUnitario`; `COUNT(DISTINCT compra)` aparte. Compras PENDIENTES con `pagoId` → cantidad e importe (`total`).
- Devueltas por DEVOLUCION: solicitudes APROBADAS con `entradaDevuelta = true`, `COUNT(DISTINCT entradaId)` y suma de `monto` por sector.
- Devueltas por CANCELACION_EVENTO: `entradas_de_reintegro` con `devuelta = true` ⋈ reintegro (origen CANCELACION_EVENTO) ⋈ compra CONFIRMADA ⋈ `detalles_compra` por `entradaId` → `COUNT(DISTINCT entradaId)` y suma de `precioUnitario` por sector. Una entrada con devolución aprobada se excluye del reintegro por cancelación, así que los dos orígenes no se solapan; igual el total de devueltas se calcula como entradas distintas.
- Devoluciones en curso: solicitudes PENDIENTES y APROBADAS con `entradaDevuelta = false`.
- Reintegros de compras CONFIRMADAS agrupados por estado y sector: DEVOLUCION → sector de la solicitud (`refundRequestId = solicitud.id`); CANCELACION_EVENTO → sector de cada entrada incluida (precio unitario). EXITOSO resta; PENDIENTE/FALLIDO van a "pendientes de reconciliación".
- Compras no confirmadas (FALLIDA o PENDIENTE) con `pagoId`: suma de `total` como "cobrado" y suma de reintegros EXITOSOS como "reintegrado".

Alternativa descartada: cargar compras y sumar en Java; más simple de escribir pero trae todo el detalle del evento a memoria y la propuesta pide consultas agregadas.

### D4. Coherencia (atomicidad de lectura) e idempotencia

- Cada reporte corre en una sola transacción `@Transactional(readOnly = true)`. Con InnoDB en REPEATABLE READ todas las consultas del reporte leen la misma instantánea, así que una compra que se confirma en medio no aparece en ventas y falta en recaudación.
- La llamada a MS1 (y a MS2 en ocupación) se hace **antes** de abrir la transacción, para no retener una conexión mientras se espera a otro servicio.
- Los endpoints son GET sin efectos: no escriben, no guardan resultados, no generan avisos. Repetirlos es seguro por definición; un 503 no deja estado intermedio.

### D5. Sectores y montos en la respuesta

- Lista de sectores = sectores que informa MS1 (en su orden) más los que aparezcan en compras y ya no estén en MS1 (con el `sectorNombre` guardado en la compra). Así un evento sin ventas muestra sus sectores en 0.
- Importes `BigDecimal` con escala 2; porcentajes con 2 decimales, `HALF_UP`; capacidad 0 → 0.00.
- Ocupación: solo sectores que devuelve MS2, con el nombre de MS1 (o `null` si MS1 no lo tiene).

### D6. Estructura

- `web/ReporteController` (4 GET) → `servicio/ReporteService` (autorización + armado) → repositorios existentes con métodos `@Query` nuevos y proyecciones (interfaces, como `CantidadPorTipo`).
- DTOs `web/dto/Reporte*Respuesta` (records) con un encabezado común (`eventoId`, `eventoNombre`, `estadoEvento`, `generadoEn`, `hayOperacionesPendientes`).
- `hayOperacionesPendientes` se calcula igual en los cuatro reportes, con tres conteos sobre MS3: compras PENDIENTES con `pagoId` no nulo (una PENDIENTE sin pago es una compra iniciada que todavía no se cobró y no cuenta), solicitudes de devolución en curso (PENDIENTE, o APROBADA sin entrada devuelta) y reintegros PENDIENTES o FALLIDOS de compras del evento.
- `ReservasCliente.obtenerDisponibilidad(eventoId)` → `Optional<Disponibilidad>` (vacío ante 404).

### D7. Fragmento para el API Gateway (no se aplica)

Se entrega en `docs/gateway-reportes-ms3.md` para el dueño del Gateway:
- Ruta `ms3-reportes`: `Path=/reportes/eventos/{eventoId}/{reporte}` (un solo segmento después del evento), `Method=GET`, `uri: ${gateway.services.ms3-url:${MS3_URL:http://localhost:8083}}`.
- Seguridad: `.pathMatchers(HttpMethod.GET, "/reportes/eventos/*/*").hasAnyRole("ORGANIZADOR", "ADMIN")` antes de `anyExchange()`. La titularidad la valida MS3.

### D8. Documentación y pruebas

- `static/openapi.yaml`: tag "Reportes", 4 paths con 200/401/403/404/503 y esquemas de respuesta; `DocumentacionOpenApiTest` verifica que existan.
- Colección Postman de MS3: carpeta "Reportes" con los 4 reportes como ADMIN y como organizador dueño, y casos de organizador ajeno (403) y cliente (403).
- Pruebas: repositorio (consultas agregadas sobre MySQL de prueba), web con `PruebaIntegracion` y dobles (`EventosFalso` con `organizadorId`, `ReservasFalso` con disponibilidad configurable y caída), y cliente REST de disponibilidad con `ServidorDePrueba`.

## Risks / Trade-offs

- [Las consultas de reintegros por cancelación unen cuatro tablas] → Volumen chico en el TP; filtros por `eventoId` y `compraId` (ya indexados como FK lógicas o PK). Si las pruebas de carga lo muestran, se agrega un índice en `compras(evento_id, estado)`.
- [MS1 sin `organizadorId` (versión vieja)] → Solo ADMIN puede ver reportes; no se filtran datos a terceros.
- [Ocupación y ventas pueden diferir (MS2 cuenta lo vendido hoy; ventas cuenta histórico)] → Es el comportamiento pedido por la propuesta; la documentación de cada reporte lo aclara.
- [Instantánea solo dentro de MS3] → Los datos de MS1/MS2 se leen en otro momento; se informa `generadoEn` y los pendientes.

## Migration Plan

Sin cambios de esquema (`ddl-auto=update` no agrega nada). Despliegue: MS3 primero (los endpoints nuevos no rompen nada); después el dueño del Gateway aplica el fragmento. Rollback: quitar la ruta del Gateway; los endpoints de MS3 quedan inaccesibles desde afuera.
