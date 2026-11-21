# Modificación de eventos

## Why

MS1 aún no permite modificar la fecha, el horario o el lugar de un evento. Compras necesita recibir los datos vigentes para procesar estas modificaciones sin perder actualizaciones entregadas fuera de orden.

## What Changes

- Incorporar `PUT /eventos/{eventoId}` para modificar `fechaHora` y/o `lugarId`, autorizado para el organizador titular o un administrador; validar identidad, roles completos, datos, estado e intención de cancelación.
- Guardar la ciudad del lugar nuevo y evitar escrituras, incrementos de versión y avisos cuando no existan cambios reales.
- Para eventos publicados o agotados, persistir en la misma transacción un aviso versionado a Compras con el estado completo; reutilizar el dispatcher, sus encabezados y su política de reintentos.
- Invalidar la caché del catálogo después del commit y reflejar los cambios en las consultas existentes, sin agregar estados públicos.
- Documentar el contrato en README, OpenAPI y Postman; verificarlo con pruebas que usen MongoDB de Testcontainers.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: modificación autorizada, validaciones, persistencia y notificación versionada a Compras.

## Impact

Afecta la API y el dominio de eventos, autorización, transacciones MongoDB, outbox, caché de catálogo, documentación y pruebas de MS1, además de su contrato saliente con MS3. El Gateway actualmente solo enruta `GET` para el detalle: habilitar `PUT` allí es una dependencia para acceso por Gateway. Se conservan sectores y disponibilidad al cambiar de lugar, sin migrarlos.
