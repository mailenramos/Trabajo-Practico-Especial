# Proposal

## Why

MS8 todavía no ofrece gestión de preferencias propia, aunque MS1 ya contempla a este servicio como consumidor del catálogo de eventos. Este cambio entrega primero la capacidad mínima para que cada cliente administre sus intereses, sin adelantar la lógica de recomendaciones ni sus integraciones.

## What Changes

- Crear el microservicio MS8 con Spring Boot, Java 21 y Maven, siguiendo la estructura, configuración, errores, identidad y documentación de MS1.
- Persistir en MongoDB standalone una preferencia por usuario, con `tiposEvento`, `artistas`, `ciudades` y `actualizadoEn`.
- Exponer `GET`, `PUT` y `DELETE /preferencias/me`, restringidos al rol `CLIENTE`, con semántica definida para ausencia de identidad, rol incorrecto, listas vacías, reemplazo y eliminación idempotente.
- Validar que cada valor se recorte y no quede vacío ni duplicado.
- Incorporar pruebas de autorización para cada endpoint y pruebas de persistencia con Testcontainers.
- Añadir configuración Docker y Compose, README de ejecución y variables de entorno, y una colección Postman mínima.
- Mantener fuera de alcance las recomendaciones, la consulta interna de eventos publicados, los avisos a Notificaciones, outbox, caché, circuit breakers e interfaz.

## Capabilities

### New Capabilities

- `preferencias-cliente`: consulta, reemplazo y eliminación de las preferencias del cliente autenticado.

### Modified Capabilities

Ninguna.

## Impact

- Nuevo servicio en `Backend/mcsv-preferencias-recomendaciones`, puerto `8089`, paquete `com.ticketya.preferencias` y base `ticketya_preferencias`.
- Nuevas rutas REST bajo `/preferencias/me`; la identidad se recibe en `X-User-Id` y `X-User-Roles`, con la convención compartida por MS1.
- Nuevas configuraciones de Maven, aplicación, Docker Compose, pruebas, README y Postman; no se modifican otros microservicios.
