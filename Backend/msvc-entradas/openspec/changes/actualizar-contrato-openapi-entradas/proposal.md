# Actualizar el contrato OpenAPI de entradas

## Motivo

La especificación `openapi-entradas.yaml` describe un contrato anterior. Los DTO que expone el
microservicio ahora usan `idEntrada`, `fila` y `numero`, y varios identificadores externos son
texto. La especificación también publica un formato de error distinto al que genera
`GlobalExceptionHandler`.

## Cambio propuesto

- Actualizar los esquemas de solicitudes y respuestas para que coincidan con los DTO públicos.
- Documentar que los identificadores de evento, sector y cliente son cadenas, no UUID.
- Reemplazar `idAsiento` por `fila` y `numero` en los contratos que representan un lugar.
- Actualizar el esquema y los ejemplos de error al formato real de la API.
- Corregir los requisitos de encabezados de los endpoints públicos y las respuestas `400` que
  puede devolver el controlador.
- Incrementar la versión del contrato a `1.1.0`.

## Impacto

Solo se modifica documentación del contrato en `openapi-entradas.yaml`; no cambian endpoints,
persistencia ni lógica Java. Los consumidores deben adaptar sus modelos cliente si todavía usan
`id`, `idAsiento`, `estado` o `detalles` de la especificación anterior.
