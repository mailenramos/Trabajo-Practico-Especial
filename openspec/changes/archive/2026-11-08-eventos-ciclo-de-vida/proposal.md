# Proposal

## Why

El microservicio necesita permitir que los organizadores preparen y publiquen eventos y que los administradores gestionen su venta y cancelación. La publicación depende de inicializar la disponibilidad en Reservas, por lo que el evento solo debe hacerse visible cuando esa inicialización se confirma.

## What Changes

- Crear eventos en borrador asociados a un organizador y a un lugar existente del mismo servicio.
- Configurar precios por sector, calcular el precio mínimo al publicar y permitir definir el límite de entradas por cliente.
- Incorporar publicación, suspensión/reactivación de ventas, cancelación y transiciones automáticas de estado.
- Integrarse con Disponibilidad y Reservas y con Compras y Devoluciones mediante los contratos REST confirmados.
- Exponer una consulta interna del evento para Compras y notificar cancelaciones con reenvío programado si el aviso queda pendiente.
- Mantener las rutas internas entre servicios fuera del API Gateway y normalizar el formato JSON de errores.
- Configurar el servicio para escuchar en el puerto 8081 por defecto, con `PORT` como sobrescritura.

## Capabilities

### New Capabilities

- `eventos`: creación, configuración de precios y límite por cliente, publicación y ciclo de vida administrativo y automático de eventos.

### Modified Capabilities

Ninguna. La consulta y administración existente de lugares y sectores no cambia.

## Impact

Afecta al microservicio Gestión de lugares, sectores y eventos, su API pública detrás del API Gateway, la persistencia MongoDB y las integraciones directas con Disponibilidad y Reservas y con Compras y Devoluciones. Las llamadas internas no usan Gateway ni autenticación entre servicios.
