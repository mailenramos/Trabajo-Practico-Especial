## Por qué

Entradas (MS5) cambia su contrato en la Entrega 2 y eso afecta a Compras (MS3):

- Una entrada pasa a `UTILIZADA` cuando se valida su QR. Hoy `EstadoEntrada` la lee como `DESCONOCIDO`.
- Aprobar la devolución de una entrada ya usada devuelve 409 `ENTRADA_UTILIZADA`.
- Al cancelar un evento, Entradas registra el cierre del evento y lo informa con `cerrado: true`. La consigna exige confirmar que el cierre se completó antes de terminar la cancelación.
- Con el evento cerrado, emitir entradas devuelve 409 `EVENTO_CERRADO`. Una compra cobrada después del cierre quedaría sin entradas y sin reintegro.
- Entradas exige `X-Service-Token` y `X-Correlation-Id` en las llamadas internas. Hoy Compras no manda ninguno de los dos.
- Entradas agrega `GET /internal/compras/{idCompra}/entradas` para consultar sin emitir.

## Qué cambia

- `EstadoEntrada` incorpora `UTILIZADA`.
- El cliente de Entradas manda `X-Service-Token` y `X-Correlation-Id` en todas sus llamadas.
- La cancelación de un evento no se da por completa hasta que Entradas responde `cerrado: true`.
- Un 409 `EVENTO_CERRADO` al emitir se trata como un conflicto de negocio (sin reintentos) y dispara el reintegro automático de esa compra.
- Se agrega al cliente la consulta de entradas por compra, para confirmar el estado de una emisión incierta.
- Se actualizan README, `openapi.yaml`, `historias-ms3.md` y el diagrama de secuencia de la compra.

## Fuera de alcance

- Rechazar automáticamente una solicitud APROBADA cuya entrada resultó UTILIZADA: queda con el error guardado para el administrador. Se puede evaluar después.
- Cambios en Entradas, en el Gateway o en otros servicios.
- Reintegros por entradas ya utilizadas en eventos que no se cancelan.
