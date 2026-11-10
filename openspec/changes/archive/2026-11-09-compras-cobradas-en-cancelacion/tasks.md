# Tasks

## 1. Un pago nunca confirma una compra que dejó de estar pendiente

- [x] 1.1 En `PagoService.pasos`, verificar el resultado de `compras.cambiarEstado(compraId, PENDIENTE, CONFIRMADA)`: si es 0, no confirmar y responder con el estado real de la compra (`respuestaCompra(200, compraId)`), con un log de advertencia. Prueba en `PagoTest` que, con la compra pasada a `FALLIDA` por fuera (vía `compras.cambiarEstado`) después de iniciar un pago interrumpido, el pago retomado con la misma clave responde la compra `FALLIDA` sin cobrar, confirmar ni emitir. Verificar con `.\mvnw test -Dtest=PagoTest`.
- [x] 1.2 Prueba en `PagoTest`: pago nuevo (clave nueva) sobre una compra `FALLIDA` sin cobro no llama a Pagos (`pagos.pedidosDeCobro()` sin cambios) y responde 200 con la compra fallida. Verificar con la prueba.

## 2. Cierre de compras pendientes con pago registrado

- [x] 2.1 Agregar en `CancelacionService` el cierre de una compra pendiente (`cerrarPendiente`) según el design (decisión 2): bloquear la compra; si está `CONFIRMADA`, devolver la indicación de seguir por `procesarCompra`; si está `PENDIENTE` con `pagoId`, aplicar `cambiarEstado(PENDIENTE → FALLIDA)` y, solo si devuelve 1, registrar el reintegro por el total (motivo "Evento cancelado", origen `CANCELACION_EVENTO`, `refundRequestId` nuevo) y las `EntradaDeReintegro` de los detalles con `entradaId`; todo en una transacción; después `completar(reintegroId, false)`. Verificar que compila con `.\mvnw test-compile`.
- [x] 2.2 En `procesar`, recorrer primero las compras `PENDIENTE` del evento (cada una con su propio manejo de errores, como las confirmadas) y después volver a consultar y procesar las `CONFIRMADA`. Reemplazar la prueba `lasComprasPendientesYFallidasNoGeneranReintegro` de `CancelacionEventosTest` por: compra pendiente con pago aprobado y emisión fallida (`entradas.fallarProximas(3)`) → queda `FALLIDA`, un reintegro `CANCELACION_EVENTO` por el total con `reason` "Evento cancelado", `pagos.reintegrosNuevos() == 1`; compra ya fallida (pago rechazado) → sin reintegro. Verificar con `.\mvnw test -Dtest=CancelacionEventosTest`.
- [x] 2.3 Prueba: compra pendiente con pago aprobado cuyo reintegro falla (`pagos.setResultadoReintegro(FALLIDO)`) → compra `FALLIDA`, reintegro `FALLIDO`; `POST /reintegros/{id}/reintento` lo deja `EXITOSO` usando el `/retry` de Pagos, sin crear otro reintegro. Verificar con la prueba.

## 3. Cierre de compras pendientes sin pago registrado

- [x] 3.1 En `cerrarPendiente`, si la compra no tiene `pagoId`, consultar `listarPagosPorCompra` con la compra bloqueada: con un pago `APROBADO`, guardar su `paymentId` en la compra y seguir como en 2.1; sin aprobado, pasarla a `FALLIDA` sin reintegro. Si la consulta lanza `ServicioExternoException`, la compra sigue `PENDIENTE` y el error se registra en la cancelación (queda `EN_PROCESO`). Verificar que compila.
- [x] 3.2 Prueba "cobro hecho pero respuesta perdida": compra pendiente sin `pagoId` y un pago `APROBADO` registrado en `PagosFalso` (`pagos.registrarPago(...)` o `setPerderRespuesta(true)` en el pago) → la cancelación guarda el `pagoId`, deja la compra `FALLIDA` y pide un reintegro por el total contra ese pago. Verificar con la prueba.
- [x] 3.3 Prueba "sin cobro": compra pendiente sin pagos en Pagos (y otra solo con un pago `RECHAZADO`) → `FALLIDA`, sin reintegro, `pagos.consultasPorCompra()` incrementado. Verificar con la prueba.
- [x] 3.4 Prueba "Pagos caído en la consulta": con `pagos.setCaido(true)` la compra sigue `PENDIENTE`, sin reintegro, la cancelación queda `EN_PROCESO` con el error registrado y una compra confirmada del mismo evento recibe igual su reintegro (la parte de reintegros del doble se mantiene disponible; si `setCaido` afecta también a los reintegros, agregar al doble un `setConsultaPorCompraCaida`). Verificar con la prueba.

## 4. Idempotencia y retoma

- [x] 4.1 En `procesar`, recorrer también las compras `FALLIDA` del evento con un reintegro `CANCELACION_EVENTO` en estado `PENDIENTE` y llamar a `completar(reintegroId, false)`. Prueba: con `pagos.setProximosReintegrosSinRespuesta(1)` el reintegro de una compra pendiente cerrada queda `PENDIENTE` y la cancelación se procesa; al retomar (`cancelacionService.procesar(EVENTO_ID)`) se pide con el mismo `refundRequestId` y Pagos no lo duplica (`reintegrosNuevos() == 1`). Verificar con la prueba.
- [x] 4.2 Prueba de aviso repetido: tras procesar una cancelación con una compra pendiente cobrada, un segundo aviso responde 200 y un `procesar` explícito no registra otro reintegro ni cambia la compra (`reintegros.findByCompraIdAndOrigen(...)` con tamaño 1). Verificar con la prueba.
- [x] 4.3 Prueba de entradas conocidas: compra pendiente con pago aprobado cuya emisión asignó entradas pero falló la confirmación final (simular dejando `entradaId` en los detalles y la compra `PENDIENTE`) → esas entradas quedan incluidas en el reintegro y pasan a devuelta con el reintegro exitoso. Verificar con la prueba.

## 5. Concurrencia con un pago retomado

- [x] 5.1 Agregar a `EntradasFalso` una demora configurable en `emitir` (`setDemoraEmisionMs`), limpiada en `reiniciar()`, para sostener el bloqueo de la compra durante un pago. Verificar que compila.
- [x] 5.2 Prueba en una clase nueva `CancelacionConPagoSimultaneoTest`: compra pendiente con pago aprobado y emisión fallida; luego, al mismo tiempo (`PagoTest.alMismoTiempo`), el cliente retoma el pago con la misma clave (con demora de emisión) y se procesa la cancelación (`cancelacionService.procesar(EVENTO_ID)`). Comprobar el invariante: exactamente un reintegro `CANCELACION_EVENTO` para la compra; si la compra quedó `CONFIRMADA`, sus entradas pasaron a devuelta y el reintegro es por las vigentes; si quedó `FALLIDA`, no hubo emisión nueva ni confirmación de venta extra y el reintegro es por el total; nunca un solo cobro duplicado (`pagos.cobrosNuevos() == 1`). Repetir el escenario varias veces (por ejemplo, 5) para cubrir ambos órdenes. Verificar con la prueba.
- [x] 5.3 Pruebas deterministas de cada orden: (a) primero se completa el pago retomado y después se procesa la cancelación → camino de confirmadas; (b) primero la cancelación y después el pago retomado → 200 con la compra `FALLIDA`, sin cobro, confirmación ni emisión. Verificar con la prueba.

## 6. Documentación

- [x] 6.1 En `compras/src/main/resources/static/openapi.yaml`: en `POST /eventos-cancelados`, reemplazar el punto 3 ("Las compras pendientes o fallidas no generan reintegro") por el cierre de las pendientes (con pago aprobado: fallida + reintegro por el total; sin cobro: fallida sin reintegro; Pagos sin respuesta: queda pendiente y se retoma) y el límite de las entradas emitidas sin respuesta; en `POST /compras/{compraId}/pago`, aclarar que una compra cerrada por la cancelación de su evento responde 200 con la compra `FALLIDA` y su reintegro, sin cobrar. Verificar con `.\mvnw test -Dtest=DocumentacionOpenApiTest`.
- [x] 6.2 En `docs/historias-ms3.md`: actualizar E-1 (la viñeta de compras pendientes o fallidas pasa a describir el cierre de las pendientes y el límite aceptado); mover a "Resueltos" el punto abierto 1 (clave de idempotencia: no vence en la Entrega 1, con el porqué y la limpieza posible en la Entrega 2) y el 5 (compra pendiente cobrada en un evento cancelado), y renumerar los abiertos. No tocar los diseños archivados. Verificar releyendo la sección "Decisiones tomadas y puntos abiertos".

## 7. Verificación integral

- [x] 7.1 Correr `.\mvnw test` en `compras/` y confirmar que pasa toda la suite.
- [x] 7.2 Correr `openspec validate compras-cobradas-en-cancelacion --strict` sin errores.

## Workflow follow-up

- Archivar el cambio con `/opsx:archive` cuando esté revisado (sincroniza `cancelacion-de-eventos` y `pago-de-compra`).
