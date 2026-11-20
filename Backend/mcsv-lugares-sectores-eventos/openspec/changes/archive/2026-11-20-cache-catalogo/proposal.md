# Propuesta: caché local del catálogo

## Why

Las búsquedas públicas consultan repetidamente MongoDB para datos que cambian con menor frecuencia. Una caché local acotada reduce esas lecturas sin guardar datos que dependen de la identidad.

## What Changes

- Incorporar Spring Cache y Caffeine solo para la búsqueda pública, con TTL y tamaño máximo configurables.
- Mantener fuera de la caché el detalle, la disponibilidad, las consultas internas y los datos que dependen de identidad; preservar los contratos actuales.
- Centralizar la invalidación ante escrituras que cambien el catálogo público, incluidas las transiciones de estado y suspensión de venta.
- Documentar la configuración y la consistencia eventual entre instancias; probar aislamiento, expiración e invalidación.

## Capabilities

### New Capabilities

- `catalogo-cache`: caché local y reglas de elegibilidad, expiración e invalidación para el catálogo público.

### Modified Capabilities

Ninguna; se preservan los requisitos existentes de `eventos`.

## Impact

Dependencias y configuración Spring de MS1, servicio de búsqueda, escrituras que afectan el catálogo, pruebas y README. El detalle conserva su comportamiento actual. No se modifican los contratos HTTP, el Gateway ni otros microservicios. La caché es por instancia: no se agrega caché distribuida ni sincronización entre instancias.
