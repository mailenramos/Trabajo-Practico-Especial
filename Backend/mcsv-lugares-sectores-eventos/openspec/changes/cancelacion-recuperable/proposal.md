# Cancelación recuperable

## Why

Actualmente MS1 cierra la venta en Reservas antes de guardar la cancelación. Un corte entre ambas operaciones puede dejar la venta cerrada sin cancelación ni aviso a Compras, y una falla de Reservas no deja trabajo pendiente. Además, Fila virtual no recibe la cancelación.

## What Changes

- Persistir primero la intención de cancelar y recuperarla mediante el scheduler y la outbox existentes; tras la confirmación de Reservas, cancelar localmente y crear los avisos a Compras y MS7 de forma atómica.
- Añadir un aviso estable a MS7 con `PUT` sin body para toda cancelación, incluso si `requiereFila=false`; admitir métodos y body opcional en la outbox sin alterar los avisos anteriores.
- Mantener los códigos actuales: `200` al completar la cancelación local, `503` si el primer intento no puede completarla y `409` para un evento ya cancelado; repetir no duplica efectos. Un `503` puede dejar la intención persistida para recuperación automática.
- Mientras haya una intención pendiente, suspender y reactivar responden `409` y la finalización programada omite el evento; no se agrega un lease compartido entre operaciones.
- Conservar reintentos limitados, lease, `ERROR_FINAL`, invalidación de caché después del commit y propagación de headers comunes; no asumir que la aceptación de Compras completa los reintegros.
- Configurar `FILAS_BASE_URL`, actualizar README y Postman y probar con Testcontainers, sin MongoDB local.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: recuperación de cancelaciones, avisos a Fila virtual y soporte de métodos HTTP en outbox.

## Impact

Cambian el flujo administrativo de cancelación, su coordinación con Reservas, la outbox y su dispatcher, configuración, Compose, documentación, Postman y pruebas. No se agregan estados visibles al catálogo ni transacciones distribuidas; no se migran cancelaciones históricas.
