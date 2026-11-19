# Proposal

## Why

MS8 necesita consultar el catálogo vigente de eventos y recibir un aviso durable cuando MS1 publica uno. MS1 ya ofrece rutas internas sin identidad de usuario y cuenta con una outbox transaccional y un dispatcher general; este cambio reutiliza ambas capacidades para recomendaciones.

## What Changes

- Agregar `GET /internal/eventos-publicados` para consultar eventos `publicado` y `agotado`, con paginación y los datos requeridos por MS8.
- Al publicar un evento, guardar el cambio y un aviso `PUBLICACION` en la outbox dentro de la misma transacción MongoDB.
- Entregar el aviso a `POST {PREFERENCIAS_BASE_URL}/internal/preferencias/eventos-publicados` mediante el dispatcher existente, usando el ID estable `publicacion-{eventoId}`.
- Configurar `PREFERENCIAS_BASE_URL` con default local `http://localhost:8089` y default Docker `http://preferencias:8089`.
- Actualizar la especificación existente de eventos y la tabla de configuración del README.

## Scope

El endpoint devuelve solo eventos en estado `publicado` o `agotado`; MS8 decide si excluye eventos finalizados, cancelados o con fecha pasada. La publicación continúa esperando primero la confirmación de Reservas. Un fallo posterior de Preferencias no revierte la publicación ni cambia la respuesta al usuario.

Quedan fuera de alcance la caché, endpoints para gestionar avisos, modificaciones de eventos, filtros adicionales y cambios al dispatcher que no sean necesarios para registrar o entregar este nuevo tipo de aviso.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: agregar la consulta interna para MS8 y ampliar la publicación para registrar y entregar un aviso outbox.

## Impact

Afecta la consulta interna y persistencia de eventos, la integración del ciclo de publicación con la outbox existente, la configuración de MS1 y su README. No requiere cambios en el contrato de reservas ni en las reglas generales del dispatcher.
