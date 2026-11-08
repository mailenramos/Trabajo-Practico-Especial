# Proposal

## Why

El microservicio de Gestión de lugares, sectores y eventos necesita definir cómo se registran y consultan lugares y sectores antes de poder asociarlos a eventos. Este cambio establece el contrato de las US-1.1 a US-1.4, con validación de datos y acceso exclusivo para administradores en las operaciones de gestión.

## What Changes

- Definir el registro y consulta de lugares, incluyendo coordenadas geográficas validadas.
- Definir sectores numerados mediante filas y asientos, con unicidad de fila y número dentro del sector.
- Definir sectores no numerados con capacidad positiva.
- Definir autorización administrativa, códigos HTTP de éxito y error, y respuestas para recursos inexistentes.
- Excluir la gestión de eventos y la búsqueda, que se especificarán en otros cambios.

## Assumptions

- Usamos MongoDB desde la Entrega 1; esto se aparta de la tabla de la consigna (base relacional en la Entrega 1) y se informará a los docentes.

## Capabilities

### New Capabilities

- `lugares-y-sectores`: Registro, administración y consulta de lugares y sectores.

### Modified Capabilities

## Impact

- API pública del microservicio expuesta a través del API Gateway; identidad recibida en `X-User-Id` y `X-User-Role`.
- Persistencia MongoDB propia del microservicio en documentos de lugares con sectores embebidos; ningún otro servicio accede a esta base.
- La comunicación con otros servicios usa IDs, sin compartir documentos ni tablas.
