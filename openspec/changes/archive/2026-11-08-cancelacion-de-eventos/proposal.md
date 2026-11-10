## Por qué

Cuando un administrador cancela un evento en el servicio de eventos, los clientes con
compras confirmadas tienen que recuperar su dinero sin pedirlo ni esperar una aprobación
(E-1). Hoy este servicio no recibe el aviso de cancelación ni pide esos reintegros. El
cambio `devoluciones` ya dejó la coordinación de reintegros y su reintento, que este
cambio reutiliza (E-2).

## Qué cambia

- Endpoint interno `POST /eventos-cancelados` (lo llama Eventos) con `eventoId`, `motivo`
  y `canceladoEn`. Idempotente por `eventoId`: 202 si acepta un aviso nuevo (o uno que
  sigue en proceso), 200 si ese evento ya se procesó.
- Por cada compra confirmada del evento, un reintegro automático con un `refundRequestId`
  propio (la misma clave de idempotencia) y `reason` "Evento cancelado".
- El monto de cada reintegro es lo que todavía no se reintegró de la compra: el precio
  unitario de sus entradas vigentes. Las entradas con una devolución aprobada no se
  vuelven a reintegrar.
- Las entradas del evento pasan a en reintegro con la marcación masiva de Entradas y, con
  el reintegro exitoso, a devuelta.
- Un aviso repetido no pide otro reintegro, aunque la marcación de Entradas responda
  `entradasMarcadas: 0`.
- Las solicitudes de devolución pendientes de entradas del evento se cierran como
  rechazadas, porque esas entradas se reintegran por la cancelación.
- Los reintegros por cancelación que fallan se reintentan con `POST /reintegros/{id}/reintento`,
  el mecanismo de `devoluciones`; con el reintegro exitoso, sus entradas pasan a devuelta.

## Capacidades

### Capacidades nuevas
- `cancelacion-de-eventos`: recepción idempotente del aviso de cancelación y devolución
  automática de las compras confirmadas del evento.

### Capacidades modificadas
- `reintegros`: reintegro por cancelación de un evento (monto, motivo, identificador
  propio) y su reintento, que deja devueltas las entradas incluidas.
- `devolucion-de-entradas`: una solicitud pendiente de una entrada de un evento cancelado
  se cierra como rechazada.

## Fuera de alcance

- Cancelar el evento, liberar o bloquear sus reservas (servicios 1 y 2).
- Compras pendientes o fallidas del evento: no se pide reintegro (E-1). Ver el riesgo en
  el diseño sobre una compra pendiente que ya fue cobrada.
- Tarea periódica que reintente sola los reintegros fallidos: los reintenta un
  administrador, como en `devoluciones`.
- Notificar al cliente.

## Dependencias de otros servicios

- Servicio 1 (eventos): llama a `POST /eventos-cancelados`; reenvía el aviso si este
  servicio no responde. Contrato acordado.
- Servicio 4 (pagos): reintegros con `refundRequestId` propio, `Idempotency-Key` y
  `reason` obligatorio. Contrato acordado.
- Servicio 5 (entradas): `POST /internal/eventos/{idEvento}/entradas/reintegro` (responde
  la cantidad marcada en `entradasMarcadas`, sin los IDs) y `POST /internal/entradas/{id}/devolucion`.
  Es una propuesta de Entradas.

## Impacto

- Código en `compras/`: tabla nueva de cancelaciones recibidas, tabla que asocia cada
  reintegro por cancelación con sus entradas, servicio y endpoint de cancelación,
  `EntradasCliente` con la marcación por evento, procesamiento en segundo plano y la rama
  de reintento de `ReintegroController` para este origen.
- Documentación: `openapi.yaml` y `docs/historias-ms3.md`.
