# Tasks

## 1. Pedido de emisión con el contrato confirmado

- [x] 1.1 Renombrar en `EntradasCliente` `SolicitudEmision.lineas` → `lugares` y `LineaEmision(sectorId, sectorNombre, …)` → `LineaEmision(idSector, nombreSector, fila, numero)`; ajustar `PagoService.emitirEntradas` y `EntradasFalso.emitir`. Verificar que compila con `.\mvnw test-compile`.
- [x] 1.2 En `EntradasClienteRestTest`, verificar que el pedido lleva `lugares[].idSector`, `lugares[].nombreSector` (exactamente ese nombre), `fila`/`numero` enteros, `evento {id, nombre, comienzaEn}` y no lleva `lineas`, `sectorId` ni `sectorNombre`. Verificar con la prueba.
- [x] 1.3 Agregar a `EntradasClienteRestTest` el caso de sector no numerado: dos líneas con `fila` y `numero` como `null` explícito en el JSON del pedido y una respuesta con `null` que se conserva en `EntradaEmitida`. Verificar con la prueba.
- [x] 1.4 Agregar una prueba de pago (junto a `PagoTest`) con dos lugares de un sector no numerado: la compra queda confirmada y cada detalle recibe un `entradaId` distinto; y un reintento de emisión envía el mismo pedido (doble registra el pedido recibido). Verificar con la prueba.

## 2. Consulta de la entrada y código de error

- [x] 2.1 En `ClienteRest`, agregar `codigo` a `CuerpoDeError` y hacer que `codigoDeError` devuelva `error` o, si falta o está vacío, `codigo`. Prueba en `ClienteRestTest` con ambos formatos y con ninguno. Verificar con la prueba.
- [x] 2.2 Ampliar el record `Entrada` con `idCompra`, `idEvento`, `sectorId`, `sectorNombre`, `fila`, `numero`, `codigoQr`, y en `EntradasClienteRest.consultar` leerlos de `idCompra`, `evento.id`, `sector.id`, `sector.nombre`, `fila`, `numero`, `codigoQr` (el sector nunca de `idSector`). Ajustar los usos en servicios y `EntradasFalso` (mantener `agregar(id, cliente, estado, comienzaEn)` y sumar una variante completa). Prueba en `EntradasClienteRestTest` con la respuesta completa del contrato, incluido un caso con `idSector` en la raíz que no se usa. Verificar con la prueba.
- [x] 2.3 En `consultar`, devolver vacío solo ante 404 con código `ENTRADA_NO_ENCONTRADA` (en `error` o en `codigo`); otro 404 lanza `ServicioExternoException`. Pruebas en `EntradasClienteRestTest` para los tres casos. Verificar con la prueba.
- [x] 2.4 Prueba de `SolicitarDevolucionTest` (o la existente de entrada inexistente) que siga respondiendo 404 `ENTRADA_NO_ENCONTRADA` con el doble. Verificar con `.\mvnw test -Dtest=SolicitarDevolucionTest`.

## 3. Conflicto de estado en los cambios de estado

- [x] 3.1 Crear `ticketya.cliente.EstadoEntradaConflictoException extends ServicioExternoException` (javadoc: conflicto de estado, reintentar sin cambios no lo arregla, distinto de una caída) y lanzarla en `pasarAEnReintegro`/`pasarADevuelta` ante un 409, con el código leído en el mensaje. Actualizar los javadoc de `EntradasClienteRest` quitando "PROVISORIO" donde el contrato quedó confirmado (dejar la nota en la marcación por evento). Reemplazar en `EntradasClienteRestTest` la prueba `cualquierOtraRespuestaEsUnaFallaDelPaso` por: 200 al repetir es éxito; 409 `{"error":"TRANSICION_INVALIDA"}` y 409 `{"codigo":"TRANSICION_INVALIDA"}` lanzan `EstadoEntradaConflictoException`; 500 lanza `ServicioExternoException` que no es conflicto. Verificar con la prueba.
- [x] 3.2 Agregar a `ManejadorDeErrores` un handler de `EstadoEntradaConflictoException` (409 `CONFLICTO_DE_ESTADO_EXTERNO`) antes del genérico, con una prueba unitaria o de web que lo dispare. Verificar con la prueba.
- [x] 3.3 En `EntradasFalso`, agregar un modo configurable de transición inválida (por ejemplo `rechazarTransicion(idEntrada)`) que lance `EstadoEntradaConflictoException`. Verificar que compila y que `reiniciar()` lo limpia.
- [x] 3.4 Prueba en `RetomarDevolucionTest` (o `AprobarDevolucionTest`): con transición inválida al pasar a en reintegro, la solicitud queda aprobada, incompleta en `ENTRADA_EN_REINTEGRO`, `ultimoError` indica conflicto de estado (no "no responde"), no se libera el lugar ni se pide reintegro; al quitar el rechazo y reintentar, la coordinación se completa sin duplicar. Verificar con la prueba.
- [x] 3.5 Prueba de devolución con transición inválida al pasar a devuelta: reintegro exitoso, solicitud incompleta en `ENTRADA_DEVUELTA` con error de conflicto de estado. Verificar con la prueba.
- [x] 3.6 Prueba en `CancelacionEventosTest` (o `ReintegrosEndpointTest`): reintegro por cancelación exitoso con una entrada en conflicto; las demás quedan devueltas, `ultimoError` empieza con `ENTRADA_DEVUELTA` e indica conflicto; el reintento posterior no pide otro reintegro a Pagos y limpia el error. Verificar con la prueba.

## 4. Documentación

- [x] 4.1 En `docs/historias-ms3.md`: renombrar la sección a "Entradas (5) — contrato confirmado" con los campos de la tabla del design, cambiar la frase inicial ("la propuesta del servicio 5" → contrato confirmado), quitar el párrafo "Por confirmar con Entradas" y el punto abierto 2 (renumerar), dejando la marcación por evento como único pendiente de Entradas si sigue sin confirmar. Agregar a "Resueltos" la fila del contrato de Entradas (409 `TRANSICION_INVALIDA` como conflicto de estado, código en `error` o `codigo`). Verificar releyendo que no queden menciones a "propuesta" o "por confirmar" de Entradas salvo la marcación.
- [x] 4.2 En `compras/src/main/resources/static/openapi.yaml`, revisar las descripciones que mencionan Entradas (aprobación de devolución, reintento de reintegro) y agregar que un paso puede quedar incompleto por conflicto de estado de la entrada; si se agrega el código `CONFLICTO_DE_ESTADO_EXTERNO`, sumarlo a la tabla de errores. Verificar con `DocumentacionOpenApiTest`.

## 5. Verificación integral

- [x] 5.1 Correr `.\mvnw test` en `compras/` y confirmar que pasa toda la suite (incluidas las pruebas existentes que usan `EntradasFalso`).
- [x] 5.2 `openspec validate contrato-entradas --strict` sin errores.

## Workflow follow-up

- Confirmar con Entradas la marcación por evento (`POST /internal/eventos/{idEvento}/entradas/reintegro`) y la envoltura de la respuesta de emisión.
- Archivar el cambio cuando esté revisado.
