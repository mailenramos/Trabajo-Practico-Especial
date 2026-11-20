# Propuesta: personal de acceso por evento

## Why

Entradas (MS5) necesita consultar si una persona está autorizada para validar QR en un evento y que una revocación tenga efecto inmediato. MS1 administra los eventos, pero aún no registra su personal de acceso.

## What Changes

- Agregar asignación, listado y revocación por evento para su organizador propietario o un administrador; resolver el correo en MS6 y exigir un usuario activo con `PERSONAL_ACCESO`.
- Persistir una sola asignación por par evento/usuario, con estado activo o revocado y fechas; impedir duplicados activos y permitir reactivación.
- Ofrecer a MS5 una consulta interna sin caché que devuelva `autorizado` según el estado actual.
- Enviar `X-Service-Token` al consultar MS6, usando `servicio.token`.
- Documentar y configurar la URL de MS6: `http://localhost:8087` fuera de Docker y `USUARIOS_BASE_URL` explícita en Docker, sin inventar el nombre del servicio.

## Capabilities

### New Capabilities

- `personal-acceso`: gestión de asignaciones, consulta de autorización e integración con MS6.

### Modified Capabilities

Ninguna.

## Impact

Nuevos endpoints, colección MongoDB e índice único; cliente HTTP de MS6, configuración, pruebas, README y Postman. No se agrega validación de token a rutas entrantes, caché, historial de auditoría completo ni cambios en MS5, MS6 o el Gateway.
