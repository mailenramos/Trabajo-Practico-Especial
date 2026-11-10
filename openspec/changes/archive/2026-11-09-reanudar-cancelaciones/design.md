## Context

Ver proposal.md (Why). Estado actual observado en el código:

- `CancelacionService.recibir` hace `cancelaciones.saveAndFlush(new CancelacionEvento(...))` (sin transacción propia del servicio, así que el `INSERT` se confirma ahí), después `lanzar(eventoId)` (`ejecutor.execute(() -> procesar(eventoId))`) y devuelve `ACEPTADO`; `CancelacionController` responde 202. En producción el ejecutor es el `applicationTaskExecutor` que configura Spring Boot (asincrónico); en las pruebas, `DoblesConfig` lo reemplaza por un `SyncTaskExecutor`.
- Aviso repetido: si la cancelación está `EN_PROCESO` y `tomadaEn + plazoEnProceso` ya pasó, `cancelaciones.retomar(eventoId, tomadaEnLeida, ahora)` (UPDATE condicional sobre `estado = EN_PROCESO and tomadaEn = :tomadaEnLeida`) decide quién la toma; si devuelve 1, se lanza `procesar`.
- `procesar` **siempre** pide la marcación a Entradas (`marcarEntradasDelEvento`) y la suma en `entradasMarcadas`, aunque ya se haya hecho; si falla, guarda `MARCACION: ...` en `ultimoError` y suma 0. No queda registro distinguible de "marcación hecha".
- Después recorre fallidas con reintegro pendiente, pendientes y confirmadas; si todas salen bien, `terminar` la deja `PROCESADA`. Cada camino por compra es repetible: bloqueo de la compra, reintegro único por compra y origen, `EntradaDeReintegro.devuelta` y consulta del estado de la entrada antes de pasarla a devuelta.
- No hay `@EnableScheduling` en la aplicación. Las pruebas `@SpringBootTest` son 7 clases: `PruebaIntegracion` (base de las de `web`), 5 de `repositorio` con su propio `@TestPropertySource` y `ComprasApplicationTests` sin propiedades.

## Goals / Non-Goals

**Goals:**
- Que una cancelación interrumpida se termine sin depender de un aviso de Eventos.
- Una sola toma atómica, compartida por el aviso repetido y la reanudación automática.
- No volver a pedir a Entradas una marcación ya hecha.
- Poder desactivar la tarea, y tenerla desactivada en las pruebas.

**Non-Goals:**
- Renovar `tomadaEn` durante el procesamiento (latido).
- Reintentar reintegros `FALLIDO` automáticamente.

## Contratos

Sin cambios. Eventos ya reintenta el aviso hasta un 200/202 y no lo reenvía después; con este cambio no hace falta que lo reenvíe. Entradas: `POST /internal/eventos/{idEvento}/entradas/reintegro` se pide una sola vez por cancelación con éxito (sigue siendo contrato a acordar con Entradas, sin cambios de forma).

## Decisions

### 1. Toma compartida: `tomarSiVencida(CancelacionEvento c)`

Se extrae de `recibir` la lógica de toma: si `c` está `EN_PROCESO` y `c.getTomadaEn() + plazoEnProceso` es anterior a ahora (truncado a milisegundos, como hoy), llama a `cancelaciones.retomar(eventoId, c.getTomadaEn(), ahora)` y, solo si devuelve 1, lanza `procesar`. La usan el aviso repetido y la reanudación. Como el UPDATE compara el `tomadaEn` leído, si dos actores leen el mismo valor solo uno actualiza la fila: el otro ve 0 filas y no procesa. Funciona igual entre instancias, porque la exclusión la da la base.

Alternativa descartada: `SELECT ... FOR UPDATE` de la cancelación durante todo el procesamiento. Tendría una transacción abierta por minutos y llamadas a otros servicios con el bloqueo tomado.

### 2. Reanudación: `CancelacionService.reanudarVencidas()`

```
limite = ahora - plazoEnProceso
para cada c en cancelaciones.findByEstadoAndTomadaEnBefore(EN_PROCESO, limite):
    try { si tomarSiVencida(c) → contar } catch (RuntimeException e) { log y seguir }
devuelve cuántas tomó
```

Método público, sin dependencias de la planificación: es lo que llaman las pruebas. El procesamiento se lanza con el mismo `ejecutor` que usa el aviso, así el hilo de la tarea no queda ocupado por una cancelación larga; en las pruebas el ejecutor es sincrónico y `reanudarVencidas()` vuelve con el procesamiento terminado.

### 3. Tarea programada, desactivable

- Clase nueva `ticketya.servicio.ReanudacionDeCancelaciones` (o en un paquete `tarea`), anotada `@Component`, `@ConditionalOnProperty(name = "compras.cancelacion.reanudacion-activa", havingValue = "true", matchIfMissing = true)`, con
  `@Scheduled(fixedDelayString = "${compras.cancelacion.intervalo-reanudacion:60s}", initialDelayString = "${compras.cancelacion.intervalo-reanudacion:60s}")` que llama a `reanudarVencidas()`.
- `@EnableScheduling` en una configuración propia (`ProgramacionConfig`) con el mismo `@ConditionalOnProperty`: con la propiedad en `false` no se registra la tarea ni el soporte de planificación.
- `fixedDelay` (y no `fixedRate`): la próxima revisión arranca recién cuando terminó la anterior, así una revisión lenta no se superpone consigo misma.
- Formato de la duración: Spring Framework 7 acepta en `fixedDelayString` el formato simple (`60s`, `5m`) además de ISO-8601; se verifica con una prueba de configuración (tarea 2.3). Si no lo aceptara, se usa `PT60S` como valor por defecto y se documenta.

### 4. Marcación una sola vez: `marcacionHecha`

`CancelacionEvento` gana `marcacionHecha` (boolean, falso por defecto). `procesar` pide la marcación solo si es falsa; con éxito, `registrarMarcacion` la pone en verdadero junto con la cantidad; si falla, queda en falso y se reintenta en el próximo procesamiento. `ddl-auto=update` agrega la columna; en MySQL una columna `bit not null` nueva queda en 0 en las filas existentes, así que las cancelaciones viejas vuelven a pedir la marcación una vez (repetible del lado de Entradas).

Alternativa descartada: deducirlo de `entradasMarcadas != null` o de `ultimoError`. `registrarMarcacion` pone 0 también cuando falla, y `ultimoError` lo pisa el error de una compra.

### 5. Desactivada en las pruebas

`compras.cancelacion.reanudacion-activa=false` en los `@TestPropertySource` de `PruebaIntegracion` y de las 5 pruebas de `repositorio`, y un `@TestPropertySource` nuevo en `ComprasApplicationTests`. Una prueba de configuración con `ApplicationContextRunner` comprueba que la tarea se registra por defecto y no se registra con `false`.

### Atomicidad e idempotencia

- **Toma:** UPDATE condicional sobre `(eventoId, estado = EN_PROCESO, tomadaEn = leída)`; 1 fila = tomada, 0 = la tomó otro.
- **Procesamiento repetido:** cada compra se procesa con su fila bloqueada, con un reintegro único por compra y origen guardado antes de llamar a Pagos (mismo `refundRequestId` al repetir), y las entradas se consultan antes de pasarlas a devuelta. La marcación se salta si ya se hizo.
- **Terminar:** `terminar` solo pasa a `PROCESADA` si todas las compras salieron bien; si no, queda `EN_PROCESO` con su `tomadaEn` y la próxima revisión pasada el plazo la retoma.

## Risks / Trade-offs

- [Un procesamiento vivo que tarda más que el plazo puede ser retomado en paralelo] → ya pasaba con un aviso repetido. Las protecciones por compra impiden duplicar reintegros o cierres; el costo son llamadas repetidas. Mitigación: el plazo por defecto (5 min) es mucho mayor que un procesamiento normal. Un latido queda para más adelante.
- [Una cancelación que falla siempre (por ejemplo, una compra con un error permanente) se reintenta cada plazo indefinidamente] → queda visible en `ultimoError`; el costo es bajo (un intento por plazo). Se documenta.
- [Varias instancias ejecutan la revisión a la vez] → la toma atómica hace que cada cancelación la procese una sola; las demás instancias solo hacen la consulta.
- [El ejecutor rechaza la tarea después de guardar la cancelación] → el aviso responde con error, Eventos reintenta y recibe 202 sin que se procese; la revisión automática la retoma pasado el plazo. Este caso hoy quedaba colgado.

## Migration Plan

Desplegar con la configuración por defecto (`reanudacion-activa=true`). La columna nueva se agrega sola. Las cancelaciones que hoy estén colgadas `EN_PROCESO` se retoman en la primera revisión después del arranque. Rollback: volver a la versión anterior o poner `compras.cancelacion.reanudacion-activa=false`; la columna extra no molesta.
