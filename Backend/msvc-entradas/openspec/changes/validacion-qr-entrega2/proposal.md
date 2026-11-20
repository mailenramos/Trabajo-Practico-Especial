## Why

En la Entrega 1, Gestión de entradas emite las entradas y guarda un QR (UUID) por entrada, pero no valida su uso ni registra ingresos. Tampoco impide emitir entradas de un evento ya cancelado, no lee los roles del usuario y deja las rutas internas sin protección. La Entrega 2 (sección 2.5 de la propuesta de arquitectura) pide la validación real del QR, el control de acceso por evento y la coordinación con la cancelación.

## What Changes

- Estado `UTILIZADA` y registro de ingresos (fecha, hora, usuario, validación excepcional).
- `POST /eventos/{idEvento}/ingresos`: validación de QR para `PERSONAL_ACCESO` asignado (consulta a MS1 en cada validación) o `ADMIN`.
- Competencia atómica entre validación (`VIGENTE` → `UTILIZADA`) y bloqueo por devolución (`VIGENTE` → `EN_REINTEGRO`); `409 ENTRADA_UTILIZADA`.
- Cierre persistente e idempotente del evento cancelado, coordinado por evento con emisión y validación; `409 EVENTO_CERRADO`.
- `GET /internal/compras/{idCompra}/entradas` para recuperar emisiones inciertas.
- Consultas públicas por rol (`X-User-Roles`), `X-Service-Token` opcional en `/internal/**` y `X-Correlation-Id`.
- Asiento `UTILIZADA` sigue ocupado (índice único).
- Ruta del Gateway para la validación y `GET /entradas` limitado a `CLIENTE` y `ADMIN`.

## Servicios de los que depende

- MS1: `GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` (acordado).
- MS3: maneja `ENTRADA_UTILIZADA`, `EVENTO_CERRADO`, `cerrado: true` y la consulta por compra (acordado).
- API Gateway: propaga `X-User-Id`, `X-User-Roles` y `X-Correlation-Id`.

## Fuera de alcance

- Generar la imagen QR (el QR sigue siendo el UUID).
- Consulta de entradas para `ORGANIZADOR` por titularidad de eventos.
- Ventana horaria para validar.
- Decidir si todos los servicios validan `X-Service-Token`: queda configurable (vacío = desactivado).
