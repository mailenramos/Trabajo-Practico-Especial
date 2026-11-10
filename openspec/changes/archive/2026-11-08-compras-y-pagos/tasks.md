## 1. Base del servicio
- [x] 1.1 Crear el proyecto Spring Boot (Java 21) con dependencias: Web, Data JPA, MySQL, Validation
- [x] 1.2 Configurar la conexión a MySQL por variables de entorno y el puerto 8083
- [x] 1.3 Leer los encabezados X-User-Id y X-User-Role en cada pedido
- [x] 1.4 Manejo global de errores con el formato {timestamp, status, error, mensaje, campos?}

## 2. Modelo de datos
- [x] 2.1 Entidad Compra: ID UUID, estado (pendiente, confirmada, fallida), clienteId, reservaId, eventoId, eventoNombre y eventoFechaInicio, total, medio de pago y fecha
- [x] 2.2 Entidad DetalleCompra: una fila por entrada, con sectorId y sectorNombre, fila (texto) y número opcionales, precio unitario histórico y entradaId (sin cantidad)
- [x] 2.3 Entidad ClaveIdempotencia con clave primaria compuesta (clienteId + clave), huella del pedido, estado, resultado y claveCobro (la clave que se envía a Pagos)
- [x] 2.4 Repositorios y transición de estado con actualización condicional
- [x] 2.5 Pruebas del repositorio sobre la transición de estado y la clave de idempotencia (la misma clave en dos clientes distintos no choca)
- [x] 2.6 Agregar pagoId (ID del pago en Pagos, vacío hasta que se aprueba el cobro) a la entidad Compra
- [x] 2.7 Entidad Reintegro (refundRequestId único, pagosReintegroId, estado pendiente/exitoso/fallido, intentos, monto, motivo, origen y fechas) y su repositorio
- [x] 2.8 Pruebas del repositorio de reintegros: refundRequestId único y guardado de pagoId

## 3. Clientes de otros servicios
- [x] 3.1 Interfaces para eventos, reservas, pagos y entradas
- [x] 3.2 Implementaciones REST con los contratos confirmados
- [x] 3.3 Dobles de prueba para cada interfaz
- [x] 3.4 Cliente de Pagos con los nombres exactos del contrato (campos en inglés, estados APROBADO/RECHAZADO/EXITOSO/FALLIDO): cobro, consulta por compra, reintegro, reintento y consulta de reintegro
- [x] 3.5 Configurar la URL base de cada servicio externo en application.properties (Pagos en el puerto 8084)

## 4. Iniciar compra (C-1, P-3)
- [x] 4.1 Servicio que valida la titularidad de la reserva y consulta precios, nombres y fecha del evento
- [x] 4.2 Cálculo del total y registro de la compra pendiente, con una fila de detalle por lugar (en sectores no numerados, una por cada unidad). El total tiene como máximo 2 decimales
- [x] 4.3 Endpoint POST /compras y errores de reserva ajena, inexistente y no vigente
- [x] 4.4 Pruebas de los tres escenarios de C-1
- [x] 4.5 Una compra por reserva: si el mismo cliente repite POST /compras con la compra pendiente, devolver esa compra (200) sin registrar otra ni consultar a Eventos; si ya está confirmada o fallida, 409 COMPRA_YA_INICIADA; dos pedidos simultáneos reciben la misma compra. Con pruebas de los tres casos

## 5. Verificar la reserva (C-2)
- [x] 5.1 Consulta del estado de la reserva antes del cobro
- [x] 5.2 Cortar el flujo e informar al cliente si está vencida o liberada
- [x] 5.3 Pruebas de C-2

## 6. Pago y resultado (C-3, C-4, C-5)
- [x] 6.1 Endpoint POST /compras/{id}/pago con encabezado Idempotency-Key obligatorio
- [x] 6.2 Validar el medio de pago (CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER, DIGITAL_WALLET): cualquier otro da 400 sin llamar a Pagos
- [x] 6.3 Cobro: enviar a Pagos el compraId como purchaseId, el total y el medio de pago, con la claveCobro como Idempotency-Key. Interpretar el resultado por el campo status, no solo por el código HTTP
- [x] 6.4 Pago aprobado: guardar el pagoId, confirmar la venta, emitir las entradas, guardar los entradaId y pasar a confirmada
- [x] 6.5 Confirmación rechazada: no emitir entradas ni confirmar la compra (el reintegro del cobro se decide y se completa con el cambio `devoluciones`)
- [x] 6.6 Pago rechazado: liberar los lugares y pasar a fallida
- [x] 6.7 Reintentos del pago: mismo resultado, operación en curso (202) y clave con datos distintos (409). La clave se acota por cliente
- [x] 6.8 Emisión de entradas fallida: hasta 3 intentos y la compra queda pendiente
- [x] 6.9 Repetir el pago retoma desde el último paso hecho, sin volver a cobrar. Si la compra ya tiene pagoId, no se cobra de nuevo
- [x] 6.10 Estado incierto del cobro (corte antes de recibir la respuesta): al repetir el pago, consultar el pago por compra; si existe, usarlo; si no, repetir el cobro con la misma claveCobro
- [x] 6.11 Pruebas de C-3, C-4 y C-5
- [x] 6.12 Prueba de dos pagos simultáneos de la misma reserva: solo uno prospera
- [x] 6.13 Consulta de pagos por compra según la respuesta de Pagos: el cliente REST y el doble devuelven la lista de GET /payments/purchase/{purchaseId} (200, vacía si no hay pagos) y el pago usa solo el APROBADO; lista vacía o sin aprobado: repetir el cobro con la misma claveCobro (también tras un 409 del cobro). El doble rechaza siempre DIGITAL_WALLET. Con pruebas

## 7. Consulta de compras (C-6, P-1)
- [x] 7.1 Endpoints GET /compras y GET /compras/{id} con detalle, nombres y estado
- [x] 7.2 Acceso denegado a compras de otro cliente
- [x] 7.3 Pruebas de C-6 y P-1

## 8. Documentación
- [x] 8.1 Documentar los endpoints con OpenAPI (método, ruta, parámetros, códigos, ejemplos JSON y códigos de error)
