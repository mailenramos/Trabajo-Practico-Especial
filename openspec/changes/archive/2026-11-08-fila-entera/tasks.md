## 1. Modelo y migración

- [x] 1.1 DetalleCompra.fila y SolicitudDevolucion.fila pasan a Integer (null en sectores no numerados). Ajustar las pruebas de repositorio con filas numéricas y verificar que pasan
- [x] 1.2 Script compras/src/main/resources/db/migracion-fila-entera.sql: consulta que lista filas no numéricas y ALTER TABLE ... MODIFY fila INT NULL en detalles_compra y solicitudes_devolucion. Verificar corriéndolo sobre ticketya_compras y comprobando con SHOW COLUMNS que la columna quedó INT

## 2. Error de contrato

- [x] 2.1 ContratoInvalidoException (subclase de ServicioExternoException) y handler en ManejadorDeErrores: 502 CONTRATO_INVALIDO. ClienteRest.llamar la lanza cuando falla la lectura del cuerpo. Verificar con una prueba de ClienteRest contra el servidor de prueba (cuerpo ilegible da ContratoInvalidoException; sin respuesta sigue dando ServicioExternoException)

## 3. Clientes de otros servicios

- [x] 3.1 Reservas: DetalleReserva.fila y LugarDevuelto.fila a Integer; la liberación por devolución envía fila como número. Verificar con ReservasClienteRestTest: lectura de una reserva con fila 10, fila "A" da ContratoInvalidoException, y cuerpo de liberación con "fila": 10
- [x] 3.2 Entradas: LineaEmision.fila y EntradaEmitida.fila a Integer; la respuesta se lee con idSector (o sectorId/sector), fila y numero enteros, y una fila no numérica da ContratoInvalidoException. Verificar con EntradasClienteRestTest: emisión con idSector y fila 10, fila "A" da error de contrato, y pedido enviado con "fila": 10
- [x] 3.3 Dobles de prueba (ReservasFalso, EntradasFalso) y datos de prueba (PruebaIntegracion, PruebaDevoluciones) con filas numéricas. Verificar que compilan y que la suite completa pasa

## 4. Servicio y API

- [x] 4.1 CompraService, PagoService y DevolucionService con fila Integer; CompraRespuesta y DevolucionRespuesta devuelven fila como número. Verificar con IniciarCompraTest, ConsultaComprasTest y AprobarDevolucionTest ajustados (jsonPath de fila numérico, liberación con fila entera)
- [x] 4.2 Prueba de punta a punta: una reserva con fila no numérica hace que POST /compras responda 502 CONTRATO_INVALIDO sin registrar la compra (doble de Reservas que simula el error de contrato)
- [x] 4.3 Prueba de punta a punta: una emisión que devuelve fila no numérica cuenta como emisión fallida; tras 3 intentos el pago responde 202 con la compra pendiente y sin entradas

## 5. Documentación

- [x] 5.1 openapi.yaml: fila integer en los esquemas de compra y devolución, ejemplos con fila 10, código CONTRATO_INVALIDO (502) y el rol ADMIN como confirmado. Verificar con DocumentacionOpenApiTest y comprobando que no queden "fila: A"
- [x] 5.2 openspec/config.yaml (la fila del asiento es un entero) y docs/historias-ms3.md (convención de lugar, contratos de Reservas y Entradas con fila entera, rol ADMIN confirmado y quitado de los puntos abiertos). Verificar con grep que no quede "texto" asociado a la fila ni "a confirmar" asociado al rol
- [x] 5.3 Quitar el "a confirmar" del rol ADMIN en Permisos y application.properties (los design.md archivados no se tocan: son el registro de lo decidido en su momento). Verificar con grep sobre compras/src

## 6. Verificación final

- [x] 6.1 .\mvnw test en compras y openspec validate fila-entera sin errores

## Workflow follow-up

- Correr el script de migración en cada base de desarrollo del grupo que tenga datos de compras.
- Archivar el cambio con /opsx:archive cuando esté revisado.
