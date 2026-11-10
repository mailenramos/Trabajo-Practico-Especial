## Context

Ver proposal.md (Why). Estado actual observado en el código:

- `EntradasCliente.SolicitudEmision(idCompra, idCliente, evento, lineas)` y `LineaEmision(sectorId, sectorNombre, fila, numero)` se serializan tal cual: hoy el pedido sale con `lineas[].sectorId/sectorNombre`, que no es el contrato confirmado.
- `EntradasClienteRest` arma su `RestClient` con `RestClient.builder()` (mapper por defecto, sin `NON_NULL`), así que los `null` de `fila`/`numero` ya salen explícitos. Hay que fijarlo con una prueba.
- `consultar` lee solo `idEntrada`, `idCliente`, `estado`, `evento.comienzaEn`; cualquier 404 se trata como inexistente.
- `pasarAEnReintegro`/`pasarADevuelta`: cualquier no-2xx termina en `ServicioExternoException` (genérica), que `DevolucionService.coordinar` y `CancelacionService.completar` registran en `ultimoError` como si fuera una caída.
- `ClienteRest.codigoDeError` lee solo `error` del cuerpo.
- Precedente: `ContratoInvalidoException extends ServicioExternoException` distingue un error de contrato sin romper los `catch` existentes.

## Goals / Non-Goals

**Goals:**
- Que el pedido y la lectura respeten exactamente el contrato confirmado.
- Distinguir en el registro de un paso un conflicto de estado (409 `TRANSICION_INVALIDA`) de una caída.
- Tolerar los dos formatos de error de Entradas durante su transición.

**Non-Goals:**
- Cambiar el orden de los pasos, las reglas de reintento o el modelo de datos.
- Persistir los datos nuevos de la consulta.

## Contrato con Entradas (MS5) — confirmado

| Operación | Pedido | Respuesta |
| --- | --- | --- |
| `POST /internal/entradas/emision` | `idCompra`, `idCliente`, `evento {id, nombre, comienzaEn}`, `lugares [{idSector, nombreSector, fila, numero}]`; `fila`/`numero` enteros, `null` en sector no numerado (una línea por lugar) | 201 nueva / 200 repetición, por entrada `idEntrada`, `idSector`, `fila`, `numero`, `codigoQr` (los `null` se conservan); 409 `IDEMPOTENCIA_CONFLICTO` |
| `GET /internal/entradas/{id}` | — | `idEntrada`, `codigoQr`, `estado`, `evento {id, nombre, comienzaEn}`, `sector {id, nombre}`, `fila`, `numero`, `idCompra`, `idCliente`; 404 `ENTRADA_NO_ENCONTRADA` |
| `POST /internal/entradas/{id}/reintegro` | — | 200 (también al repetir); 409 `TRANSICION_INVALIDA` |
| `POST /internal/entradas/{id}/devolucion` | — | 200 (también al repetir); 409 `TRANSICION_INVALIDA` |
| Errores | — | Formato del grupo, código en `error`; mientras migra puede venir en `codigo` |

**Contrato a acordar:**
- `POST /internal/eventos/{idEvento}/entradas/reintegro` (marcación por evento) no figura en la confirmación: se mantiene la propuesta anterior (`entradasMarcadas`, repetible con 0) sin cambios.
- La envoltura de la respuesta de emisión (lista o `{entradas: [...]}`) no se confirmó explícitamente: se sigue leyendo de forma tolerante.

## Decisions

1. **Renombrar los campos de los records en lugar de anotar con `@JsonProperty`.** `SolicitudEmision.lugares`, `LineaEmision(idSector, nombreSector, fila, numero)`. Así el nombre en Java coincide con el del contrato y no hay que recordar anotaciones. Alternativa descartada: `@JsonProperty` sobre los nombres viejos (dos nombres para lo mismo). Impacto: `PagoService.emitirEntradas`, `EntradasFalso`, pruebas.

2. **Ampliar el record `Entrada`** con `idCompra`, `idEvento`, `sectorId`, `sectorNombre`, `fila`, `numero`, `codigoQr`, leídos de `idCompra`, `evento.id`, `sector.id`, `sector.nombre`, etc. Los usos actuales (titular, estado, fecha) no cambian. Se mantiene en `EntradasFalso` el `agregar(id, cliente, estado, comienzaEn)` actual como atajo, más una variante completa, para no tocar todas las pruebas.

3. **404 de la consulta:** vacío solo si el código (de `error` o `codigo`) es `ENTRADA_NO_ENCONTRADA`; otro 404 es `ServicioExternoException`. Evita que una ruta mal configurada se lea como "la entrada no existe" (que en una solicitud de devolución respondería 404 al cliente). Alternativa descartada: cualquier 404 es inexistente (comportamiento actual, más permisivo pero oculta errores de configuración).

4. **`codigoDeError` lee `error` y, si falta o está vacío, `codigo`.** Cambio en `ClienteRest` (`CuerpoDeError` gana el campo `codigo`); los otros clientes no se ven afectados porque sus servicios mandan `error`.

5. **Nueva `EstadoEntradaConflictoException extends ServicioExternoException`** en `ticketya.cliente`, lanzada por `pasarAEnReintegro`/`pasarADevuelta` ante un 409 (con el código leído en el mensaje). Se hereda de `ServicioExternoException`, como `ContratoInvalidoException`, para que los `catch` de `DevolucionService.coordinar` y `CancelacionService.completar` sigan registrando el paso como incompleto sin revertir. Lo que cambia es el mensaje registrado: "Conflicto de estado de la entrada X en Entradas (TRANSICION_INVALIDA)" en vez de "Entradas respondió 409…". Cualquier 409 en estas dos rutas se trata como conflicto de estado, aunque traiga otro código, porque el contrato solo define ese 409. En `ManejadorDeErrores` se agrega un handler específico (409 `CONFLICTO_DE_ESTADO_EXTERNO`) por si alguna vez se propaga fuera de un `catch`; hoy no ocurre.
   Alternativa descartada: excepción no relacionada con `ServicioExternoException` (obligaría a duplicar los `catch` y arriesga un 500 si se olvida uno).

6. **Cuerpo de los cambios de estado:** se sigue usando `toBodilessEntity()`: 200 (o cualquier 2xx) es éxito, también al repetir.

### Atomicidad e idempotencia

No cambian los mecanismos: la emisión sigue siendo idempotente por `idCompra` y el pedido se arma con los mismos datos del detalle en cada intento, así que los reintentos son idénticos. Los cambios de estado son repetibles (200 al repetir) y además se consulta el estado antes de pedirlos. Cada paso de la coordinación se guarda con su bandera (`entradaEnReintegro`, `entradaDevuelta`, `EntradaDeReintegro.devuelta`) dentro de la transacción con bloqueo de la solicitud o del reintegro; un conflicto de estado deja la bandera en falso y el administrador lo retoma desde ahí.

## Risks / Trade-offs

- [Entradas todavía manda `fila` como texto mientras se ajusta] → se mantiene la lectura tolerante de texto numérico; una fila no numérica sigue siendo error de contrato.
- [Entradas despliega antes o después que este servicio] → el pedido nuevo solo funciona con el Entradas confirmado; se coordina el despliegue (no hay compatibilidad hacia `lineas`).
- [Un 409 por otro motivo en los cambios de estado se registra como conflicto de estado] → el mensaje incluye el código recibido para diagnosticar.
- [404 sin código pasa a ser falla del servicio] → si Entradas manda el código solo en `codigo`, igual se reconoce por la decisión 4.

## Migration Plan

Sin migración de base. Desplegar junto con la versión de Entradas que acepta `lugares`. Rollback: volver a la versión anterior del cliente (los datos guardados no cambian).
