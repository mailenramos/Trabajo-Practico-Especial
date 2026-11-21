# Proposal

## Why

Las rutas inexistentes pueden terminar como errores internos y otros errores HTTP del framework deben conservar su estado correcto y el formato común. La búsqueda pública ya restringe los estados visibles, pero necesita una regresión HTTP explícita con eventos de distintos estados.

## What Changes

- Responder `404` ante recursos HTTP inexistentes con el JSON de error estándar y correlación.
- Mantener `405` para métodos no permitidos y responder `415` para tipos de contenido no soportados, con el mismo formato y correlación.
- Agregar pruebas MockMvc de esos errores y de la búsqueda pública con borradores y otros estados; no modificar la lógica de búsqueda ya cubierta por el filtro existente.
- Conservar el manejo seguro de errores inesperados, las rutas existentes y el comportamiento de caché.

## Capabilities

### New Capabilities

- `errores-http`: respuestas coherentes para errores HTTP de enrutamiento y contenido.

### Modified Capabilities

Ninguna: la spec `eventos` ya exige que la búsqueda devuelva solo publicados y agotados.

## Impact

- Manejador global de excepciones y pruebas HTTP de MS1; cambio observable de estado y cuerpo en rutas y tipos de contenido afectados.
- Prueba de integración de búsqueda con MockMvc y MongoDB mediante Testcontainers.
- Sin cambios de Java 21, dependencias, APIs de búsqueda ni otros servicios.
