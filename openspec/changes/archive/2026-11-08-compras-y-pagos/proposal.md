## Por qué

El microservicio 3 de TicketYa necesita un camino de compra completo: el cliente
convierte una reserva vigente en una compra, se cobra y recibe sus entradas. Es el
camino principal del sistema y fija el modelo de compra y la idempotencia sobre los
que se apoyan las devoluciones y la cancelación de eventos (cambios posteriores).

## Qué cambia

- Iniciar una compra a partir de una reserva propia vigente, con cálculo del importe
  y registro en estado pendiente, con una sola compra por reserva (C-1).
- Verificar el estado de la reserva antes de pedir el cobro (C-2).
- Pago aprobado: confirmar la venta en el servicio de disponibilidad y reservas,
  emitir las entradas y dejar la compra confirmada (C-3).
- Pago rechazado: liberar los lugares y dejar la compra fallida (C-4).
- Reintento de pago idempotente mediante una clave por operación (C-5).
- Consulta de las compras propias con su detalle y estado (C-6).
- Permisos: un cliente solo ve sus compras y solo compra sus reservas (P-1, P-3).

## Capacidades

### Capacidades nuevas
- `compra-de-entradas`: inicio de la compra desde una reserva, estados de la compra
  (pendiente, confirmada, fallida) y verificación previa de la reserva.
- `pago-de-compra`: coordinación del cobro, resultado del pago e idempotencia.
- `consulta-de-compras`: listado y detalle de las compras del cliente.

### Capacidades modificadas
Ninguna. Es el primer cambio del repositorio.

## Fuera de alcance

- Devoluciones y reintegros por solicitud (cambio `devoluciones`).
- Cancelación de eventos y reintegros automáticos (cambio `cancelacion-de-eventos`).
- Crear o controlar reservas y lugares (servicio 2).
- Procesar el cobro en sí (servicio 4) y emitir o administrar entradas (servicio 5).
- Guardar eventos y precios (servicio 1): este servicio solo los consulta.
- Autenticación con JWT (Entrega 2). En la Entrega 1 se usan los encabezados
  X-User-Id y X-User-Role.

## Dependencias de otros servicios

- Servicio 1 (eventos): consultar precios de los sectores.
- Servicio 2 (reservas): consultar el estado de una reserva, confirmar la venta y
  liberar los lugares.
- Servicio 4 (pagos): procesar el cobro y, en los cambios siguientes, los reintegros.
- Servicio 5 (entradas): emitir las entradas.

Los contratos con Eventos, Reservas y Pagos están acordados y figuran en el diseño. El de
Entradas es una propuesta de ese servicio con algunos detalles por confirmar. Pagos tiene
el contrato cerrado pero su servicio todavía no está implementado.

## Decisiones pendientes que afectan este cambio

1. Pago aprobado pero venta rechazada (la reserva venció entre el cobro y la
   confirmación): se recomienda reintegro automático y compra fallida. Pagos ya aceptó
   que es un reintegro común por el total; falta decidirlo en este servicio. Afecta el
   escenario tercero de C-3.
2. Cuánto tiempo se recuerda la clave de idempotencia (C-5). El encabezado es
   `Idempotency-Key`.
3. Las compras que quedan pendientes si el servicio se cae antes de recibir la
   respuesta del pago se recuperan al repetir el pago, consultando a Pagos el pago de
   esa compra. No hay tarea periódica en la Entrega 1.

## Impacto

- Nuevo servicio Spring Boot con base MySQL propia: compras, detalles de compra
  (con el precio unitario como referencia histórica) y estados.
- Clientes REST hacia los servicios 1, 2, 4 y 5.
- Pruebas unitarias de cada escenario, con los otros servicios reemplazados por
  dobles de prueba.