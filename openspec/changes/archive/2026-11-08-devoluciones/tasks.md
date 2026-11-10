## 1. Modelo de datos

- [x] 1.1 Entidad SolicitudDevolucion (id UUID = devolucionId, clienteId, entradaId, compraId, lugar, monto, datos del evento, estado PENDIENTE/APROBADA/RECHAZADA, motivoRechazo, fechas, resueltaPor, pasos de la coordinación, ultimoError) y su repositorio con transición condicional y lectura con bloqueo. Verificar con pruebas de repositorio: nace pendiente y una solicitud resuelta no cambia de nuevo
- [x] 1.2 Columna entradaActiva con restricción única (copia del entradaId mientras está pendiente o aprobada, NULL al rechazar). Verificar con pruebas: dos activas de la misma entrada fallan y varias rechazadas conviven
- [x] 1.3 Reintegro: motivo obligatorio y columna ultimoError; búsquedas por compraId y por estado. Repositorio de DetalleCompra con búsqueda por entradaId. Verificar con pruebas de repositorio

## 2. Clientes de otros servicios

- [x] 2.1 EntradasCliente: consultar una entrada y pasarla a en reintegro y a devuelta, con las rutas de la propuesta de Entradas, marcadas como provisorias (2xx es éxito, cualquier otra respuesta es falla). Verificar con una prueba contra un servidor HTTP local
- [x] 2.2 ReservasCliente: liberar por devolución con motivo DEVOLUCION_APROBADA, devolucionId y solo el lugar devuelto (numerado: sectorId, fila y número; no numerado: sectorId y cantidad 1). Verificar con una prueba contra un servidor HTTP local del cuerpo enviado
- [x] 2.3 Dobles de prueba de Entradas (estado por entrada, repetible), Reservas (liberación repetible por devolucionId) y Pagos (reintegro idempotente por clave, reason obligatorio, fallas configurables, retry y consulta). Verificar que compilan y que las pruebas existentes siguen pasando

## 3. Reintegros

- [x] 3.1 Servicio de reintegros: registrar y pedir a Pagos con Idempotency-Key = refundRequestId y reason; EXITOSO/FALLIDO según la respuesta; sin respuesta queda PENDIENTE; 409/400 queda PENDIENTE con ultimoError. Verificar con pruebas de cada resultado
- [x] 3.2 Reintentar: pendiente sin respuesta repite el POST con la misma clave; fallido usa /retry sobre el mismo reintegro y, ante 409, consulta el estado; exitoso no llama a Pagos. Verificar con pruebas de cada caso, incluida la de que nunca se crea un refundRequestId nuevo
- [x] 3.3 Rol de administrador configurable (compras.rol-administrador, por defecto ADMIN, sin distinguir mayúsculas) y error 403 SOLO_ADMINISTRADOR. Verificar con prueba de un usuario sin el rol
- [x] 3.4 Endpoints GET /reintegros (?estado=) y POST /reintegros/{id}/reintento, solo administrador (200 exitoso, 202 sigue pendiente o fallido, 404 REINTEGRO_NO_ENCONTRADO). Verificar con pruebas de punta a punta con los dobles

## 4. Solicitar una devolución (D-1)

- [x] 4.1 Servicio de solicitud: buscar la entrada en el detalle (404), titularidad y compra confirmada (403), solicitud activa existente (200 si es propia y pendiente, 409 DEVOLUCION_EN_CURSO si está aprobada), Entradas (titular y vigente), Eventos (cancelado, plazo de más de 72 horas) e inserción con la restricción única
- [x] 4.2 Endpoint POST /devoluciones con los errores ENTRADA_NO_ENCONTRADA, ENTRADA_AJENA, ENTRADA_NO_VIGENTE, PLAZO_VENCIDO, EVENTO_CANCELADO y 503 sin registrar nada
- [x] 4.3 Pruebas de D-1: dentro del plazo, plazo vencido (incluido el límite de 72 horas exactas), entrada no vigente, entrada ajena, inexistente, evento cancelado, solicitud repetida, dos solicitudes simultáneas y nueva solicitud tras un rechazo

## 5. Resolver una devolución (D-2, P-2)

- [x] 5.1 Rechazar: POST /devoluciones/{id}/rechazo con motivo obligatorio, solo administrador; libera entradaActiva y no llama a otros servicios. Verificar con pruebas de rechazo, rechazo sin motivo, no administrador y solicitud ya resuelta
- [x] 5.2 Aprobar: transición condicional y creación del reintegro en la misma transacción, con la fila bloqueada; 409 DEVOLUCION_YA_RESUELTA. Verificar con pruebas de aprobación de una resuelta y de dos aprobaciones simultáneas (un solo reintegro)
- [x] 5.3 Coordinación en orden (entrada a en reintegro, liberar el lugar, reintegro, entrada a devuelta) marcando cada paso y consultando el estado de la entrada antes de cambiarlo; ante una falla técnica guarda ultimoError y corta. 200 completa, 202 pendiente. Verificar con pruebas de aprobación completa y de reintegro fallido
- [x] 5.4 Retomar una devolución incompleta desde POST /reintegros/{id}/reintento: sigue desde el primer paso sin marcar, sin repetir efectos. Verificar con pruebas de falla en Entradas, falla en Reservas, retomada exitosa, paso repetido sin duplicar y entrada a devuelta tras reintentar un reintegro fallido

## 6. Consultar devoluciones (D-3, P-1)

- [x] 6.1 Endpoints GET /devoluciones (?estado=) y GET /devoluciones/{id}: el cliente ve solo las suyas, el administrador todas; estado de la solicitud y, por separado, el del reintegro; motivo del rechazo; coordinación. Verificar con pruebas de cliente con solicitudes, solicitud rechazada, solicitud ajena (403) y administrador filtrando pendientes

## 7. Venta rechazada después del cobro (C-3)

- [x] 7.1 PagoService: ante la confirmación rechazada, compra PENDIENTE a FALLIDA y, solo si cambió, reintegro automático por el total (origen VENTA_RECHAZADA, refundRequestId nuevo); respuesta 200 con la compra fallida. Reemplazar la prueba que esperaba 409 VENTA_RECHAZADA y verificar con pruebas de reintegro exitoso, reintegro fallido o sin respuesta (la compra queda fallida igual) y pago repetido sin segundo reintegro
- [x] 7.2 CompraRespuesta suma sus reintegros (id, monto, origen, estado). Verificar con pruebas de compra con devolución, compra fallida por venta rechazada y compra sin reintegros

## 8. Documentación

- [x] 8.1 openapi.yaml: endpoints de /devoluciones y /reintegros con parámetros, códigos, ejemplos y códigos de error; respuesta de compra con reintegros; pago sin el 409 VENTA_RECHAZADA. Verificar con DocumentacionOpenApiTest ampliada a los endpoints nuevos
- [x] 8.2 docs/historias-ms3.md: endpoints de devoluciones y reintegros, estado Fallida de la compra, y los puntos abiertos 1 y 2 pasan a resueltos. Verificar releyendo que no quede una decisión contradictoria
- [x] 8.3 Verificación final: .\mvnw test en compras y openspec validate devoluciones sin errores

## Workflow follow-up

- Confirmar con Entradas los puntos "a acordar" del diseño y ajustar EntradasCliente si cambian.
- Archivar el cambio con /opsx:archive cuando esté revisado.
