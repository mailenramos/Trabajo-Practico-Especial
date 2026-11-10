## Contexto

Ver `proposal.md` para el porqué. Estado actual en `compras/`:

- `DetalleCompra.fila` y `SolicitudDevolucion.fila` son `String`; las columnas son
  `varchar`. `numero` ya es `Integer`.
- `ReservasCliente.DetalleReserva` y `LugarDevuelto`, y `EntradasCliente.LineaEmision` y
  `EntradaEmitida`, llevan `fila` como `String`. `PagoService` asocia cada entrada emitida
  a su lugar comparando sector, fila y número.
- `EntradasClienteRest` lee la respuesta de emisión de forma tolerante (`JsonNode`) y busca
  el sector en `sectorId` o `sector`; Entradas lo devuelve como `idSector`.
- `ClienteRest.llamar` convierte cualquier `RestClientException` en
  `ServicioExternoException` (503 `SERVICIO_NO_DISPONIBLE`), incluido un JSON que no se
  puede leer. Hoy una fila "A" de Reservas se informaría como "servicio no disponible".
- `spring.jpa.hibernate.ddl-auto=update` no cambia el tipo de una columna existente.
- Pruebas y dobles usan la fila `"A"`.

## Objetivos / No objetivos

**Objetivos:** fila entera de punta a punta (base, clientes, API, documentación) y una
falla clara y distinguible cuando otro servicio manda una fila que no es un número.

**No objetivos:** validar rangos de fila, reintentar automáticamente un error de contrato
al iniciar una compra.

## Decisiones

### Tipo: `Integer` opcional
`fila` es `Integer` en entidades, registros de clientes y DTOs; `null` en sectores no
numerados, igual que `numero`. En JSON es un número (`"fila": 10`). `LugarDevuelto.numerado()`
sigue valiendo para fila o número presentes.

### Error de contrato distinto de "servicio no disponible"
Nueva `ContratoInvalidoException extends ServicioExternoException`. Al ser subclase, todos
los caminos que hoy manejan una falla de otro servicio (reintentos de emisión, devolución
incompleta, 503 en el pago) siguen funcionando sin cambios. El manejo global la mapea a
**502 `CONTRATO_INVALIDO`** ("el servicio X respondió datos con un formato inesperado"),
con un handler más específico que el de `ServicioExternoException`.

**Alternativa descartada:** dejar el 503 actual. Confunde "el otro servicio está caído"
(reintentar sirve) con "el otro servicio manda datos incompatibles" (reintentar no sirve).

### Dónde se detecta
- **Reservas (lectura tipada):** `DetalleReserva.fila` es `Integer` y Jackson rechaza
  `"A"`. `ClienteRest.llamar` distingue la falla de lectura del cuerpo (causa
  `HttpMessageNotReadableException`) y lanza `ContratoInvalidoException`. Al iniciar una
  compra eso termina en 502 `CONTRATO_INVALIDO` y no se registra nada. Jackson acepta por
  defecto un texto numérico (`"10"`) como entero; se mantiene por tolerancia.
- **Entradas (lectura tolerante):** la fila se lee con un método que acepta un número
  entero (o un texto numérico) y lanza `ContratoInvalidoException` ante cualquier otro
  valor. El sector se toma de `idSector` y, si falta, de `sectorId` o `sector`. Como la
  excepción es un `ServicioExternoException`, en la emisión cuenta como intento fallido:
  tras 3, la compra queda pendiente y el pago responde 202 (comportamiento ya existente).

### Lo que se envía
- Liberación por devolución a Reservas: `{"sectorId", "fila": <entero>, "numero"}`.
- Emisión a Entradas: cada línea lleva `fila` entera. El resto de los nombres del pedido
  sigue siendo provisorio.

### Migración de la base
Script `compras/src/main/resources/db/migracion-fila-entera.sql`, a correr una vez en las
bases existentes (las pruebas usan `create-drop` y no lo necesitan):

```sql
ALTER TABLE detalles_compra MODIFY fila INT NULL;
ALTER TABLE solicitudes_devolucion MODIFY fila INT NULL;
```

En modo estricto (por defecto en MySQL 8), si alguna fila tiene un valor no numérico el
`ALTER` falla y la tabla queda como estaba. El script incluye antes una consulta que lista
esos valores para corregirlos a mano. Se asume que en las bases de desarrollo solo hay
datos de prueba.

**Alternativa descartada:** pasar a `NULL` los valores no numéricos. Convertiría en "no
numerado" un lugar que sí lo era, sin aviso.

### Documentación
- `openapi.yaml`: `fila` como `integer` en los esquemas de compra y devolución, ejemplos
  con `fila: 10`, y el código `CONTRATO_INVALIDO` (502).
- `openspec/config.yaml`: la convención pasa a "la fila del asiento es un entero".
- `docs/historias-ms3.md`: convención de lugar, contrato de Reservas y Entradas con fila
  entera.
- `design.md` archivados de `compras-y-pagos` y `devoluciones`: no se modifican. Son el
  registro de lo decidido en su momento, cuando la fila era texto y el rol de administrador
  un supuesto. Lo vigente queda en este cambio, en las specs principales y en
  `docs/historias-ms3.md`.

### Rol de administrador confirmado
`X-User-Role: ADMIN` queda confirmado por el grupo. Se mantiene la propiedad
`compras.rol-administrador` (por defecto `ADMIN`) por si cambia, pero se quita de los
supuestos abiertos y de los comentarios "a confirmar" en `Permisos`,
`application.properties`, `openapi.yaml` y `docs/historias-ms3.md`.

## Riesgos / Trade-offs

- [Clientes de la API que leían `fila` como texto] → cambio incompatible marcado como
  BREAKING; la API es nueva en la Entrega 1 y la consume el front del grupo.
- [Base de desarrollo con filas "A"] → el `ALTER` falla sin perder datos; el script lista
  los valores a corregir.
- [Entradas cambia el nombre de los campos de la respuesta] → la lectura tolerante acepta
  `idSector`, `sectorId` y `sector`.

## Plan de migración

1. Desplegar el código nuevo.
2. Correr `migracion-fila-entera.sql` en `ticketya_compras` (si hay valores no numéricos,
   corregirlos antes).
3. Rollback: volver a la versión anterior y `ALTER TABLE ... MODIFY fila VARCHAR(255)`;
   los enteros se convierten a texto sin pérdida.
