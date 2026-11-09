# Proposal

## Why

Los clientes y organizadores necesitan descubrir eventos disponibles y consultar su detalle antes de comprar. El servicio ya almacena los datos del evento, la ciudad y el precio mínimo, y puede combinar esa información con la disponibilidad que administra Reservas.

## What Changes

- Agregar búsqueda paginada de eventos publicados o agotados por tipo, organizador, participante, ciudad y rango de fechas, combinando criterios con AND.
- Permitir ordenar por fecha, precio mínimo o relevancia, con búsqueda de texto libre opcional.
- Agregar el detalle público de eventos con lugar, sectores, precios y disponibilidad cuando corresponda.
- Permitir estas consultas a clientes, organizadores y administradores, y ocultar borradores a quienes no sean su organizador dueño o administrador.
- Extender la interfaz existente del cliente de Reservas para consultar disponibilidad sin hacer fallar el detalle si el servicio no responde.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: agrega búsqueda, ordenamiento, paginación y consulta pública del detalle con reglas de visibilidad y disponibilidad.

## Impact

Agrega `GET /eventos` y `GET /eventos/{eventoId}` al microservicio existente. Usa MongoDB mediante índices y consultas dinámicas con `MongoTemplate`; el detalle puede consultar Disponibilidad y Reservas usando su cliente existente. No implementa compra, reserva ni cambios de estado del evento.
