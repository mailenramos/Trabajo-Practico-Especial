## Por qué

El grupo decidió que la fila de un lugar es un número entero en todos los servicios, y
Reservas ya lo confirmó. Este servicio todavía la trata como texto (por ejemplo "A") en el
modelo, en los clientes de Reservas y Entradas, en su propia API y en la documentación.
Si no se ajusta, la lectura de una reserva con fila numérica y la emisión de entradas
quedan desalineadas con los demás servicios. Además, el grupo confirmó que el rol de
administrador es `ADMIN`, que hoy figura como supuesto abierto.

## Qué cambia

- **BREAKING** (API propia): `fila` pasa de texto a entero en las respuestas de compras
  (`lugares[].fila`) y de devoluciones (`lugar.fila`). Sigue siendo nula en sectores no
  numerados.
- `DetalleCompra.fila` y `SolicitudDevolucion.fila` pasan de texto a entero, con un script
  de migración para las bases existentes.
- Clientes REST: la fila se lee como entero de Reservas
  (`GET /disponibilidad/reservas/{id}`), se envía como entero en la liberación por
  devolución y en la emisión de entradas, y se lee como entero de la respuesta de emisión
  de Entradas, que identifica el sector con `idSector`.
- Una fila no numérica recibida de Reservas o de Entradas se trata como error de contrato:
  al iniciar una compra se rechaza sin registrarla; en la emisión cuenta como una emisión
  fallida.
- `openapi.yaml`, `openspec/config.yaml` y `docs/historias-ms3.md` dejan de decir que la
  fila es texto. Los diseños archivados no se modifican.
- El rol de administrador `ADMIN` deja de figurar como supuesto abierto.

## Capacidades

### Capacidades nuevas
Ninguna.

### Capacidades modificadas
- `compra-de-entradas`: un lugar numerado se identifica por sector, fila entera y número,
  y una reserva con una fila no numérica se rechaza como error de contrato.
- `pago-de-compra`: una respuesta de emisión con una fila no numérica cuenta como emisión
  fallida.

## Fuera de alcance

- Cambiar los otros servicios: cada uno adapta su parte.
- Validar rangos de fila (por ejemplo, filas negativas o que no existen en el sector): eso
  lo controla Reservas.
- Los campos del pedido de emisión a Entradas distintos de la fila: siguen siendo
  provisorios hasta que Entradas confirme su contrato.

## Dependencias de otros servicios

- Servicio 2 (reservas): devuelve y recibe la fila como entero. Confirmado.
- Servicio 5 (entradas): la respuesta de emisión trae `idSector`, `fila` (entero) y
  `numero` por entrada. Su contrato sigue siendo una propuesta.

## Impacto

- Código en `compras/`: entidades `DetalleCompra` y `SolicitudDevolucion`, registros de
  `ReservasCliente` y `EntradasCliente`, sus clientes REST, DTOs `CompraRespuesta` y
  `DevolucionRespuesta`, manejo global de errores (código nuevo `CONTRATO_INVALIDO`).
- Base de datos: la columna `fila` de `detalles_compra` y `solicitudes_devolucion` pasa a
  `INT NULL`.
- Pruebas y dobles de prueba con filas numéricas.
- Documentación: `openapi.yaml`, `openspec/config.yaml` y `docs/historias-ms3.md`.
