# Diseño

## Fuente de verdad

El contrato se deriva de `EntradaController`, los DTO en `dto/` y `GlobalExceptionHandler`.
Los repositorios, incluido `CopiaSectorRepository`, son de persistencia y no modifican el API
HTTP.

## Decisiones

1. `idEvento`, `idSector` e `idCliente` se describen como `string`. La base y los DTO permiten
   valores textuales de hasta 64 caracteres para los dos primeros y para el cliente; no existe
   validación UUID en el controlador.
2. La posición de un lugar se representa por `fila` (cadena opcional) y `numero` (entero
   opcional). Cuando `numero` está presente, el servicio exige también `fila` y prohíbe repetir
   la misma combinación de sector, fila y número dentro de una emisión.
3. Las respuestas devuelven `idEntrada`; ese es el nombre serializado por los records
   `EntradaResponseDTO` y `EntradaEmitidaDTO`.
4. El error serializado contiene `timestamp`, `status`, `error`, `codigo`, `mensaje` y,
   opcionalmente, `campos` como mapa de mensajes por campo. No se documentará `estado` ni
   `detalles`, porque no son producidos por el manejador global.
5. Los endpoints públicos requieren solo `X-User-Id` en este microservicio. `X-User-Role` no es
   leído ni validado por el controlador, por lo que se elimina de su requisito de seguridad.
6. Se agregan respuestas `400` donde el binding de un UUID de ruta puede fallar. Para el
   reintegro masivo, `idEvento` es un texto sin validación, por lo que se elimina el `400`
   previamente documentado.
