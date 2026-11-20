## 1. Estado UTILIZADA
- [x] 1.1 Agregar `UTILIZADA` a `EstadoEntrada` y test de `EstadoEntrada.de` (UTILIZADA y desconocido)
- [x] 1.2 Test de solicitud de devolución sobre una entrada UTILIZADA (409 `ENTRADA_NO_VIGENTE` nombrando el estado)
- [x] 1.3 Test de aprobación con 409 `ENTRADA_UTILIZADA`: la solicitud queda APROBADA con el código en el último error y no se llama a Pagos

## 2. Encabezados hacia Entradas
- [x] 2.1 Filtro que guarda `X-Correlation-Id` entrante en el MDC (o genera uno) + test
- [x] 2.2 Interceptor del cliente de Entradas con `X-Service-Token` (`SERVICIO_TOKEN`) y `X-Correlation-Id` (MDC o UUID nuevo) + tests con servidor simulado (con token, sin token, con y sin correlación)

## 3. Cierre del evento
- [x] 3.1 `marcarEntradasDelEvento` devuelve `ResultadoCierre(entradasMarcadas, cerrado)`; actualizar los dobles de prueba + test del cliente
- [x] 3.2 `CancelacionService` completa el paso solo con `cerrado: true`; si no, guarda el error y reintenta + tests (con cierre, sin campo, en false, evento sin entradas)

## 4. Evento cerrado al emitir
- [x] 4.1 `EventoCerradoException` y detección del 409 `EVENTO_CERRADO` en `emitir` + test del cliente
- [x] 4.2 `PagoService`: ante `EventoCerradoException` no reintentar, registrar el reintegro automático y el aviso `EVENTO_CANCELADO` en la misma transacción; documentar en design.md el estado final de la compra
- [x] 4.3 Tests: caso feliz, repetición sin duplicar y compra ya emitida antes del cierre

## 5. Consulta por compra
- [x] 5.1 `EntradasCliente.listarPorCompra` (lista vacía si no hay entradas) + test del cliente
- [x] 5.2 Usarla antes de reintentar una emisión incierta (con entradas: asignar; vacía: emitir) + tests (opcional si queda poco tiempo)

## 6. Cierre
- [x] 6.1 Actualizar README, `openapi.yaml` e `historias-ms3.md`
- [x] 6.2 Actualizar el diagrama de secuencia de la compra con el caso de evento cerrado y regenerar los PNG
