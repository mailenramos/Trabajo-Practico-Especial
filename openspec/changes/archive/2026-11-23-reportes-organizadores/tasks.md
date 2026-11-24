# Tasks

## 1. Clientes de otros servicios

- [x] 1.1 Agregar `organizadorId` a `EventosCliente.Evento` (constructores existentes siguen compilando) y a `EventosFalso`; verificar con un test de `EventosClienteRestTest` que lo lee de `GET /internal/eventos/{id}` y queda `null` si no viene
- [x] 1.2 Agregar `ReservasCliente.obtenerDisponibilidad(eventoId)` (`GET /disponibilidad/eventos/{eventoId}`, vacío ante 404, `ServicioExternoException` ante 5xx o sin respuesta) en `ReservasClienteRest` y `ReservasFalso` (disponibilidad configurable y caída); verificar con tests en `ReservasClienteRestTest`

## 2. Autorización de reportes

- [x] 2.1 Agregar a `Permisos` el rol organizador configurable (`compras.rol-organizador`, por defecto `ORGANIZADOR`) y la verificación de acceso a reportes de un evento (`SIN_PERMISO_REPORTES`, `EVENTO_AJENO`); verificar con tests en `PermisosTest` (admin, dueño, ajeno, cliente, `organizadorId` nulo)
- [x] 2.2 Calcular `hayOperacionesPendientes` (compras PENDIENTES con pago, devoluciones en curso, reintegros PENDIENTES o FALLIDOS); verificar con test de repositorio/servicio que una PENDIENTE sin pago no cuenta y una cobrada sí
- [x] 2.3 Crear `ReporteService.eventoAutorizado` (403 sin rol antes de llamar a Eventos, 404 `EVENTO_NO_ENCONTRADO`, 503 si Eventos cae) y `ReporteController` con el encabezado común; verificar con un test web (`ReportesAccesoTest`) de 401, 403 cliente, 403 ajeno, 404, 503 y 200 para dueño y admin

## 3. Reporte de ventas

- [x] 3.1 Consultas agregadas en `CompraRepository`: entradas e importes por sector de compras CONFIRMADAS, cantidad de compras confirmadas, y compras PENDIENTES cobradas (cantidad e importe); verificar con test de repositorio (excluye PENDIENTE y FALLIDA, precios históricos, precio 0)
- [x] 3.2 `GET /reportes/eventos/{eventoId}/ventas` con sectores de Eventos más los de compras, montos con escala 2 y `hayOperacionesPendientes`; verificar con test web (ventas por sector, evento sin ventas con sectores en 0, compra con emisión incompleta aparte, devolución no resta)
- [x] 3.3 Documentar `/reportes/eventos/{eventoId}/ventas` en `openapi.yaml` (tag Reportes, 200/401/403/404/503) y agregarlo a `DocumentacionOpenApiTest`; verificar que el test pasa

## 4. Reporte de devoluciones

- [x] 4.1 Consultas agregadas de devueltas por DEVOLUCION (solicitudes aprobadas con entrada devuelta) y por CANCELACION_EVENTO (entradas de reintegro devueltas unidas al detalle), y de devoluciones en curso; verificar con test de repositorio (entradas distintas, sin solaparse, en curso aparte)
- [x] 4.2 `GET /reportes/eventos/{eventoId}/devoluciones` (vendidas, devueltas por origen, netas, importes, en curso); verificar con test web que incluye una devolución completada, una cancelación con reintegro exitoso y una solicitud pendiente
- [x] 4.3 Documentar `/reportes/eventos/{eventoId}/devoluciones` en `openapi.yaml` y en `DocumentacionOpenApiTest`; verificar que el test pasa

## 5. Reporte de recaudación

- [x] 5.1 Consultas agregadas de reintegros de compras CONFIRMADAS por estado y sector (DEVOLUCION por sector de la solicitud; CANCELACION_EVENTO por precio de cada entrada) y de cobros/reintegros de compras no confirmadas; verificar con test de repositorio
- [x] 5.2 `GET /reportes/eventos/{eventoId}/recaudacion` (bruta, reintegros exitosos, neta por sector y total; pendientes de reconciliación y no confirmadas aparte); verificar con test web de los escenarios de la spec (devolución reintegrada, cancelación repartida por sector, reintegro FALLIDO aparte, venta rechazada aparte)
- [x] 5.3 Documentar `/reportes/eventos/{eventoId}/recaudacion` en `openapi.yaml` y en `DocumentacionOpenApiTest`; verificar que el test pasa

## 6. Reporte de ocupación

- [x] 6.1 `GET /reportes/eventos/{eventoId}/ocupacion` con MS2 consultado fuera de la transacción: porcentajes con 2 decimales `HALF_UP`, capacidad 0 → 0.00, `inventarioInicializado` falso ante 404; verificar con test web (ejemplo 40 %/20 %/25 %, reservas aparte, sin inventario, MS2 caído → 503 `SERVICIO_NO_DISPONIBLE`)
- [x] 6.2 Documentar `/reportes/eventos/{eventoId}/ocupacion` en `openapi.yaml` y en `DocumentacionOpenApiTest`; verificar que el test pasa

## 7. Postman y Gateway

- [x] 7.1 Agregar a `postman/TicketYa-MS3-Compras.postman_collection.json` la carpeta "Reportes" (4 reportes como ADMIN y como organizador dueño; cliente → 403; organizador ajeno → 403 usando un evento creado por el ADMIN, cuyo `organizadorId` es el del admin, sin crear otro usuario) con variables en el entorno local, sin secretos; verificar que el JSON es válido y cada request tiene tests de status
- [x] 7.2 Escribir `docs/gateway-reportes-ms3.md` con la ruta `ms3-reportes` (formato de `application.yml`) y el matcher de seguridad (formato de `GatewaySecurityConfiguration`); verificar que las rutas y roles coinciden con la spec

## 8. Verificación integral

- [x] 8.1 Correr la suite completa de MS3 contra MySQL y verificar 0 fallas
- [x] 8.2 Verificar que consultar un reporte no escribe nada (test web: cantidades de compras, solicitudes, reintegros y avisos iguales antes y después; dos consultas devuelven los mismos valores)
- [x] 8.3 `openspec validate reportes-organizadores --strict` sin errores

## Workflow follow-up

- Archivar el cambio con `openspec archive reportes-organizadores` cuando la revisión esté completa.
- Verificar que `openspec/specs/reportes-organizadores/spec.md` quedó creado con su Purpose.
- Pasar `docs/gateway-reportes-ms3.md` al dueño del API Gateway.
