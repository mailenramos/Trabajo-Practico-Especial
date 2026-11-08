# Tareas de implementación

- [ ] Actualizar `info.version` a `1.1.0` y la descripción general del contrato.
- [ ] Ajustar los esquemas `EventoResumen`, `SectorResumen`, `Entrada`, `LugarAEmitir`,
      `EmisionRequest`, `EntradaEmitida` y `ReintegroEventoResponse`.
- [ ] Sustituir en ejemplos y respuestas `id`/`idAsiento` por `idEntrada`/`fila`/`numero`.
- [ ] Reemplazar el esquema `Error` y sus ejemplos por el formato de `ErrorResponseDTO`.
- [ ] Quitar `UserRole` de la seguridad efectiva de `/entradas` y `/entradas/{id}`.
- [ ] Añadir `400` en las operaciones con `{id}` UUID y quitarlo de la operación de reintegro
      por evento.
- [ ] Validar sintaxis OpenAPI y contrastar cada request/response con los DTO y el controlador.
