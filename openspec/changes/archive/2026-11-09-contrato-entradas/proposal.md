## Why

El cliente de Entradas (MS5) se implementó como provisorio sobre una propuesta con detalles por confirmar. Entradas ya confirmó su contrato y el cliente actual no lo respeta en puntos concretos: el pedido de emisión manda `lineas` con `sectorId`/`sectorNombre` en lugar de `lugares` con `idSector`/`nombreSector`, la consulta no lee el sector anidado y un 409 en un cambio de estado se confunde con una caída del servicio.

## What Changes

- **BREAKING (hacia Entradas):** el pedido de `POST /internal/entradas/emision` pasa a enviar `idCompra`, `idCliente`, `evento {id, nombre, comienzaEn}` y `lugares [{idSector, nombreSector, fila, numero}]` (antes `lineas` con `sectorId`/`sectorNombre`). En un sector no numerado se envía una línea por lugar con `fila` y `numero` en `null` explícito.
- `GET /internal/entradas/{id}`: el cliente lee además `idCompra`, `evento.id`, `sector.id`, `sector.nombre` (anidado; no `idSector`), `fila`, `numero` y `codigoQr`. Un 404 con código `ENTRADA_NO_ENCONTRADA` significa que la entrada no existe.
- `POST /internal/entradas/{id}/reintegro` y `/devolucion`: un 200 (también al repetir) es éxito; un 409 `TRANSICION_INVALIDA` se informa como **conflicto de estado**, con una excepción propia distinta de la caída del servicio, y queda registrado así en el paso que falló.
- Formato de error: el cliente toma el código de negocio de `error` y, si no viene, de `codigo` (transición de Entradas al formato del grupo).
- El doble de prueba `EntradasFalso` refleja el contrato confirmado (sector anidado, `idCompra`, conflicto de estado configurable).
- `docs/historias-ms3.md`: Entradas pasa a contrato confirmado y se quitan sus puntos abiertos. `openapi.yaml` solo cambia si alguna respuesta pública de este servicio lo requiere (por ejemplo, la descripción del error de un paso con conflicto de estado).

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `pago-de-compra`: la emisión de entradas usa los campos confirmados (`lugares`, `idSector`, `nombreSector`) y conserva `fila`/`numero` nulos en sectores no numerados.
- `devolucion-de-entradas`: un cambio de estado rechazado por Entradas (409 `TRANSICION_INVALIDA`) se registra como conflicto de estado, distinto de una falla del servicio; una entrada inexistente se reconoce por el 404 `ENTRADA_NO_ENCONTRADA`.
- `reintegros`: pasar a devuelta una entrada que Entradas rechaza por transición inválida se registra como conflicto de estado, sin confundirlo con una caída.

## Impact

- **Servicios externos:** depende de Entradas (MS5), con su contrato ya confirmado. No cambia nada con Eventos, Reservas ni Pagos.
- **Código:** `ticketya.cliente.EntradasCliente` (records `SolicitudEmision`, `LineaEmision`, `Entrada`), `ticketya.cliente.rest.EntradasClienteRest`, `ClienteRest.codigoDeError`, nueva excepción de conflicto de estado en `ticketya.cliente`; usos en `PagoService`, `DevolucionService` y `CancelacionService` (solo para el nuevo tipo de error y los nuevos nombres de campos).
- **Pruebas:** `EntradasClienteRestTest`, `EntradasFalso` y las pruebas de devoluciones y reintegros que dependen del doble.
- **Documentación:** `docs/historias-ms3.md`; `compras/src/main/resources/static/openapi.yaml` si corresponde.
- **Base de datos:** sin cambios.

## Fuera de alcance

- La marcación por evento (`POST /internal/eventos/{idEvento}/entradas/reintegro`) no figura en la confirmación de Entradas: se mantiene como está y queda como contrato a acordar.
- La forma de la respuesta de la emisión (lista o `{entradas: [...]}`) no se cambia: se sigue leyendo de forma tolerante.
- No se guardan en este servicio los datos nuevos que devuelve la consulta (QR, sector anidado); solo se leen.
- No se cambia la lógica de negocio de compras, devoluciones ni cancelaciones más allá de cómo se informa el conflicto de estado.
- Los cambios del lado de Entradas (fila como entero, formato de error del grupo) los hace ese servicio.
