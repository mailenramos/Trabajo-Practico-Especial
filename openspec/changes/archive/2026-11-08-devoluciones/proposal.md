## Por qué

Un cliente que no puede asistir necesita devolver una entrada y recuperar su dinero, y un
administrador necesita controlar esas devoluciones. Además, el cambio `compras-y-pagos`
dejó sin resolver qué pasa con un cobro aprobado cuando Reservas rechaza la venta: la
compra queda pendiente con el dinero cobrado. Este cambio agrega la coordinación de
reintegros que resuelve los dos casos y que después reutiliza la cancelación de eventos.

## Qué cambia

- El cliente solicita la devolución de una entrada vigente propia hasta 72 horas antes del
  inicio del evento. La solicitud nace pendiente y no se duplica (D-1).
- Un administrador aprueba o rechaza la solicitud. Al aprobarla, el servicio pasa la
  entrada a en reintegro, libera el lugar en Reservas, pide el reintegro del precio
  unitario a Pagos y, con el reintegro exitoso, pasa la entrada a devuelta (D-2, P-2).
- El cliente consulta sus solicitudes y, por separado, el estado del reintegro de cada una
  (D-3).
- Cada reintegro queda registrado y se puede reintentar sin duplicarse, con el reintento de
  Pagos sobre el mismo reintegro (D-4).
- Si falla una de las llamadas de la aprobación distinta del reintegro, la devolución queda
  aprobada con la coordinación incompleta y se retoma desde el paso que falló. No se
  revierte nada.
- Pago aprobado y venta rechazada por Reservas: reintegro automático por el total y la
  compra pasa a fallida. Cierra la decisión pendiente de C-3.
- Al consultar una compra se ve el estado de sus reintegros.
- Endpoints nuevos: `/devoluciones` (solicitar, listar, consultar, aprobar, rechazar) y
  `/reintegros` (listar y reintentar, solo administrador).

## Capacidades

### Capacidades nuevas
- `devolucion-de-entradas`: solicitud de devolución, sus estados (pendiente, aprobada,
  rechazada), su resolución por un administrador, la coordinación de la aprobación con
  Entradas, Reservas y Pagos, y la consulta de las solicitudes.
- `reintegros`: registro de cada reintegro pedido a Pagos, su estado (pendiente, exitoso,
  fallido) y su reintento sin duplicar.

### Capacidades modificadas
- `pago-de-compra`: la confirmación de venta rechazada después de un pago aprobado pasa a
  pedir un reintegro automático por el total y deja la compra fallida.
- `consulta-de-compras`: la compra muestra el estado de sus reintegros.

## Fuera de alcance

- Cancelación de eventos y sus reintegros automáticos (cambio `cancelacion-de-eventos`).
  Este cambio deja lista la coordinación de reintegros que ese cambio reutiliza.
- Procesar el reintegro en sí (servicio 4) y administrar los estados de las entradas
  (servicio 5): este servicio solo los pide.
- Devolver una entrada a vigente o volver a reservar un lugar liberado: no hay operaciones
  inversas en Entradas ni en Reservas, por eso una aprobación no se revierte.
- Tarea periódica que reintente sola los reintegros fallidos: en la Entrega 1 los reintenta
  un administrador.
- Autenticación con JWT (Entrega 2).

## Dependencias de otros servicios

- Servicio 1 (eventos): consultar la fecha de inicio y el estado del evento para el plazo
  de 72 horas. Contrato acordado.
- Servicio 2 (reservas): liberar el lugar devuelto con el `devolucionId`. Contrato
  acordado.
- Servicio 4 (pagos): pedir, reintentar y consultar reintegros. Contrato acordado.
- Servicio 5 (entradas): consultar titular, estado y fecha de una entrada, y pasarla a en
  reintegro y a devuelta. Es una propuesta de Entradas: el formato de error, los códigos de
  respuesta de los cambios de estado y el manejo de sectores no numerados están por
  confirmar.

## Decisiones que toma este cambio

1. **Falla de una llamada distinta del reintegro al aprobar:** se registra para resolver y
   se retoma, sin revertir. La devolución queda aprobada y guarda qué pasos se hicieron;
   un administrador la reintenta y el servicio sigue desde el paso que falló. Cada paso es
   repetible sin duplicar efectos.
2. **Pago aprobado y venta rechazada:** reintegro automático por el total cobrado y compra
   fallida.

## Impacto

- Tablas nuevas o modificadas: `solicitudes_devolucion` (nueva) y `reintegros` (se agrega
  el último error y el motivo pasa a obligatorio).
- Clientes REST: nuevas operaciones de Entradas (consulta y cambios de estado) y de
  Reservas (liberación por devolución). Pagos ya tiene las de reintegro.
- `PagoService` cambia el camino de la venta rechazada.
- La respuesta de compra suma sus reintegros.
- `docs/historias-ms3.md` y `openapi.yaml` se actualizan con los endpoints nuevos y las
  decisiones.
