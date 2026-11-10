## Contexto

Ver `proposal.md` para el porqué. Estado actual en `compras/` que condiciona el diseño:

- `ReintegroService` registra, pide y reintenta reintegros (clave = `refundRequestId`,
  `reason` obligatorio). `OrigenReintegro.CANCELACION_EVENTO` ya existe sin uso.
- `POST /reintegros/{id}/reintento` distingue el origen `DEVOLUCION` (retoma la
  devolución completa) del resto (solo el reintegro).
- `DevolucionService.coordinar` ya resuelve "consultar la entrada antes de cambiarla" y la
  coordinación con la fila bloqueada; la cancelación reutiliza ese patrón.
- `SolicitudDevolucionRepository.rechazar` hace el cambio condicional a rechazada y libera
  la marca de solicitud activa.
- `Compra` guarda `eventoId`; no hay búsqueda de compras por evento.
- No hay ejecución en segundo plano configurada. Spring Boot trae un `TaskExecutor`
  (`applicationTaskExecutor`) listo para inyectar.

## Objetivos / No objetivos

**Objetivos:** responder enseguida al aviso, procesar cada compra de forma independiente
y repetible, y que ningún reintegro se pida dos veces por la misma compra.

**No objetivos:** reintentos automáticos en segundo plano, notificaciones, reintegro de
compras pendientes que ya fueron cobradas (ver riesgos).

## Decisiones

### Modelo
- **`cancelaciones_evento`** (nueva): `eventoId` (clave primaria), `motivo`,
  `canceladoEn`, `recibidaEn`, `estado` (`EN_PROCESO`, `PROCESADA`), `tomadaEn`,
  `entradasMarcadas` (total que informó Entradas, solo informativo) y `ultimoError`. La
  clave primaria hace la idempotencia por `eventoId` en la base.
- `entradasMarcadas` **se suma** entre procesamientos: una marcación repetida responde 0
  y no debe borrar lo que informó la primera.
- **`entradas_de_reintegro`** (nueva): `reintegroId`, `entradaId`, `devuelta`. Guarda qué
  entradas cubre un reintegro por cancelación, para pasarlas a devuelta cuando sea exitoso
  y para no volver a incluirlas.

### Recepción del aviso (idempotencia por `eventoId`)
`POST /eventos-cancelados` con `{"eventoId","motivo","canceladoEn"}` (`eventoId`
obligatorio; 400 `VALIDACION` si falta). Sin encabezados de usuario: es una llamada
interna de Eventos que el Gateway no expone.

1. Se intenta insertar la cancelación como `EN_PROCESO`.
2. Si ya existe:
   - `PROCESADA` → **200**, sin hacer nada.
   - `EN_PROCESO` y tomada hace menos que `compras.cancelacion.plazo-en-proceso` (por
     defecto 5 min) → **202**, sin iniciar otro procesamiento.
   - `EN_PROCESO` y tomada hace más (el servicio se cayó a mitad de camino) → se retoma con
     una actualización condicional de `tomadaEn` (solo uno la toma) → **202**.
3. Aviso nuevo → **202**.

Cuerpo de la respuesta en los dos casos: `{"eventoId", "resultado"}`, con `resultado`
`ACEPTADO` (202) o `YA_PROCESADO` (200). No informa el avance del procesamiento: con el
ejecutor en segundo plano, al responder todavía no se sabe.

El procesamiento corre en el `TaskExecutor` después de responder. Así Eventos recibe la
respuesta enseguida aunque el evento tenga muchas compras. En las pruebas se reemplaza el
ejecutor por uno sincrónico.

**Alternativa descartada:** procesar dentro del pedido. Con muchas compras el pedido
superaría el tiempo de espera de Eventos, que reenviaría el aviso.

### Procesamiento
1. Marcar las entradas del evento: `POST /internal/eventos/{eventoId}/entradas/reintegro`.
   Se guarda `entradasMarcadas`, pero no decide nada: el monto se calcula con los datos
   propios. Si Entradas no responde, se guarda `ultimoError` con el prefijo `MARCACION` y
   se sigue igual (la entrada se marca de nuevo al retomar, y cada entrada se consulta antes
   de pasarla a devuelta).
2. Por cada compra **confirmada** del evento (búsqueda nueva por `eventoId` y estado), en
   su propia transacción, con la fila de la compra bloqueada:
   1. Leer **con bloqueo** todas las solicitudes de devolución de la compra. Las
      **pendientes** se cierran con un rechazo condicional, motivo "Evento cancelado:
      reintegro automático" y `resueltaPor` "sistema": esas entradas cuentan como vigentes.
      Las **aprobadas** quedan fuera del reintegro. Como la aprobación también bloquea la
      solicitud antes de cambiarla, una aprobación simultánea espera a que termine esta
      transacción y después la ve rechazada (409 `DEVOLUCION_YA_RESUELTA`). Sin el bloqueo,
      una aprobación que entra en ese momento podía dejar la misma entrada en dos
      reintegros.
   2. Si la compra ya tiene un reintegro con origen `CANCELACION_EVENTO`, no se crea otro
      (un aviso repetido o un procesamiento retomado no duplica). Si quedó pendiente sin
      respuesta, se vuelve a pedir con la misma clave.
   3. Si no tiene: las entradas vigentes son las del detalle sin una solicitud de
      devolución aprobada. Si no queda ninguna, la compra no genera reintegro. Si quedan,
      se registra el reintegro (`refundRequestId` UUID nuevo, motivo "Evento cancelado",
      monto = suma de sus precios unitarios) y sus filas en `entradas_de_reintegro`. Esa
      transacción se confirma **antes** de llamar a Pagos: si el servicio se cae después,
      el procesamiento retomado encuentra el reintegro y lo vuelve a pedir con la misma
      clave. Después, con la fila del reintegro bloqueada, se pide a Pagos con el `pagoId`
      de la compra.
   4. Con el reintegro exitoso, cada entrada incluida pasa a devuelta (consultando antes
      su estado, como en `devoluciones`) y se marca `devuelta`. Si Entradas no responde, el
      reintegro queda exitoso con `ultimoError` = `ENTRADA_DEVUELTA: <detalle>` y las
      entradas que faltan siguen con `devuelta` en falso. Un reintento del administrador
      no vuelve a llamar a Pagos, termina de devolverlas y borra ese error.
   5. Las fallas de Pagos o de Entradas quedan registradas en el reintegro (pendiente,
      fallido o con `ENTRADA_DEVUELTA`) y la compra cuenta como procesada: las resuelve
      un administrador con el reintento. Una **falla inesperada** (por ejemplo, de la base)
      se registra en el log y en `ultimoError` de la cancelación (`COMPRA <id>: ...`), y se
      sigue con la siguiente compra: una compra no frena a las demás.
3. Al terminar, la cancelación pasa a `PROCESADA` solo si todas las compras se procesaron.
   Si alguna tuvo una falla inesperada, queda `EN_PROCESO` y el próximo aviso de Eventos,
   pasado el plazo, la retoma. Retomar es seguro: las compras que ya tienen su reintegro
   no generan otro.

Compras pendientes o fallidas: no se tocan (E-1).

### Reintento (E-2)
`POST /reintegros/{id}/reintento` suma una rama para `CANCELACION_EVENTO`: reintenta el
reintegro con `ReintegroService` y, si queda exitoso, pasa a devuelta las entradas de
`entradas_de_reintegro` que todavía no lo están. Si el reintegro ya era exitoso (quedó
con `ENTRADA_DEVUELTA`), no llama a Pagos y solo termina las entradas. 200 si queda
exitoso y con todas las entradas devueltas; 202 si algo sigue pendiente.

### Consulta
La compra ya muestra sus reintegros con su origen y estado (requisito "La compra muestra
sus reintegros"), así que el cliente ve el reintegro por cancelación sin cambios.

### Endpoint
| Método y ruta | Quién | Respuestas |
| --- | --- | --- |
| `POST /eventos-cancelados` body `{"eventoId","motivo","canceladoEn"}` | Eventos (interno) | 202 `{"eventoId","resultado":"ACEPTADO"}` (nuevo o en proceso); 200 `{"eventoId","resultado":"YA_PROCESADO"}`; 400 `VALIDACION` |

## Contratos con otros servicios

### Acordados
- **Eventos:** llama a `POST /eventos-cancelados`; 202 si se acepta, 200 si ya se procesó;
  reenvía el aviso si no hay respuesta.
- **Pagos:** `POST /api/v1/payments/{paymentId}/refunds` con `Idempotency-Key` y `reason`
  obligatorios, `/retry` y consulta del reintegro.

### Propuesta de Entradas
- `POST /internal/eventos/{idEvento}/entradas/reintegro`: marca las entradas del evento y
  responde la cantidad en `entradasMarcadas`, sin los IDs.
- `GET /internal/entradas/{id}` y `POST /internal/entradas/{id}/devolucion`.

### A acordar
- **Entradas:** si la marcación por evento es repetible (se asume que sí: repetirla
  responde `entradasMarcadas: 0`) y su formato de error.

## Riesgos / Trade-offs

- [Una compra pendiente con el pago aprobado (emisión fallida o venta todavía sin
  confirmar) no recibe reintegro, porque E-1 solo cubre las confirmadas] → queda
  registrada en el log de la cancelación; plantearlo al grupo para un cambio posterior.
- [El servicio se cae a mitad del procesamiento] → la cancelación queda `EN_PROCESO` y se
  retoma con el próximo aviso de Eventos pasado el plazo; cada compra es repetible.
- [Una compra con una falla inesperada deja la cancelación `EN_PROCESO`] → Eventos
  recibe 202 en lugar de 200 hasta que un aviso, pasado el plazo, la retome con éxito.
  Si la falla persiste, queda en `ultimoError` de la cancelación para revisarla a mano.
- [El bloqueo de las solicitudes alarga la transacción de la compra] → la transacción solo
  rechaza solicitudes y registra el reintegro; las llamadas a Pagos y Entradas van en otra.
- [Entradas no responde a la marcación] → se sigue con los reintegros; cada entrada se
  consulta y se pasa a devuelta de a una cuando el reintegro sale bien.
- [E-1 dice que el `reason` es el motivo de la cancelación] → se usa el texto fijo
  "Evento cancelado", como se pidió; `docs/historias-ms3.md` se alinea.

## Plan de migración

- `ddl-auto=update` crea las dos tablas nuevas; no hay datos que transformar.
- Rollback: la versión anterior ignora las tablas nuevas.
