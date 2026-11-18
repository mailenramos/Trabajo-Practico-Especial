# Proposal

## Why

MS1 consume actualmente un único `X-User-Role`, mientras el Gateway valida el JWT y propaga el `sub` como `X-User-Id` y todos los roles como `X-User-Roles`. Este contrato impide representar correctamente usuarios con varios roles y las consultas públicas existentes exigen autenticación.

## What Changes

- Adoptar `X-User-Id` y `X-User-Roles` como identidad y autorización de las rutas públicas; interpretar los roles como un conjunto separado por comas, sin seleccionar uno por precedencia.
- Mantener la titularidad: solo un organizador puede administrar sus propios eventos; `ADMIN` administra lugares y sectores y gestiona las operaciones administrativas actuales, incluida la cancelación.
- Hacer públicas la búsqueda y la consulta de detalle de eventos `publicado` o `agotado`; no exigir token para esas consultas.
- Mantener privados los borradores: su detalle requiere identidad autenticada y pertenecer al organizador titular o tener el rol `ADMIN`.
- Mantener `/internal/...` fuera de la autenticación de usuario.
- Actualizar las capacidades existentes de lugares/sectores y eventos; la búsqueda permanece dentro de `eventos`.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `lugares-y-sectores`: adopta los headers del Gateway, permite evaluar múltiples roles y conserva la autorización administrativa.
- `eventos`: adopta los headers del Gateway, conserva titularidad y permisos administrativos, y actualiza la visibilidad y autenticación de búsqueda y detalle.

## Impact

Afecta las reglas de autorización y las pruebas de API de MS1. No cambia la validación del JWT en el Gateway ni requiere autenticación entre microservicios.
