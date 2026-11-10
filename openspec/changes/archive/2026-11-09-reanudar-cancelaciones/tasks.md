# Tasks

## 1. Toma compartida y método de reanudación

- [x] 1.1 Desactivar la tarea en las pruebas antes de crearla: agregar `compras.cancelacion.reanudacion-activa=false` al `@TestPropertySource` de `PruebaIntegracion` y de las 5 clases de `ticketya.repositorio`, y un `@TestPropertySource` con esa propiedad en `ComprasApplicationTests`. Verificar con `.\mvnw test-compile`.
- [x] 1.2 Extraer en `CancelacionService` la toma `tomarSiVencida(CancelacionEvento)` (vencida según `plazoEnProceso`, `cancelaciones.retomar(...) == 1` y `lanzar`) y usarla en `recibir` sin cambiar su comportamiento. Verificar con `.\mvnw test -Dtest=CancelacionEventosTest` (las pruebas de aviso repetido siguen pasando).
- [x] 1.3 Agregar `CancelacionEventoRepository.findByEstadoAndTomadaEnBefore(EstadoCancelacion, Instant)` y el método público `CancelacionService.reanudarVencidas()` (devuelve cuántas tomó; un error en una no frena a las demás). Prueba en una clase nueva `ReanudacionCancelacionesTest`: una cancelación `EN_PROCESO` con `tomadaEn` de hace 10 minutos y una compra confirmada se retoma sin aviso, la compra recibe su reintegro y la cancelación queda `PROCESADA`; una tomada hace 1 minuto no se toca; una `PROCESADA` no se vuelve a procesar. Verificar con la prueba.
- [x] 1.4 Prueba de retoma sin duplicar: cancelación con una compra que ya tiene su reintegro exitoso y otra cuyo reintegro quedó pendiente sin respuesta (`pagos.setProximosReintegrosSinRespuesta(1)`), forzada a vencida; `reanudarVencidas()` no crea reintegros nuevos y pide el pendiente con el mismo `refundRequestId` (`pagos.reintegrosNuevos()` sin duplicados). Prueba de una compra que vuelve a fallar al retomar (`pagos.setCaido(true)` con una compra pendiente sin pago): la cancelación sigue `EN_PROCESO` y una segunda `reanudarVencidas()` antes del plazo no la toma. Verificar con la prueba.

## 2. Exclusión y tarea programada

- [x] 2.1 Prueba de concurrencia en `ReanudacionCancelacionesTest`: con una cancelación vencida, `PagoTest.alMismoTiempo(reanudarVencidas, aviso repetido por MockMvc)` procesa una sola vez (marcación pedida una sola vez y un solo reintegro) y el aviso responde 202; y dos `reanudarVencidas()` simultáneas toman la cancelación una sola vez (la suma de lo que devuelven es 1). Verificar con la prueba (repetirla varias veces con `@RepeatedTest`).
- [x] 2.2 Crear la tarea programada: `ProgramacionConfig` con `@EnableScheduling` y la clase de la tarea con `@Scheduled(fixedDelayString = "${compras.cancelacion.intervalo-reanudacion:60s}", initialDelayString = ...)` que llama a `reanudarVencidas()`, ambas con `@ConditionalOnProperty(name = "compras.cancelacion.reanudacion-activa", havingValue = "true", matchIfMissing = true)`. Agregar las dos propiedades a `application.properties` con su comentario. Verificar que compila y que la suite de cancelación sigue pasando.
- [x] 2.3 Prueba de configuración con `ApplicationContextRunner` (sin base de datos, con un `CancelacionService` simulado): por defecto se registra la tarea; con `reanudacion-activa=false` no se registra; con `intervalo-reanudacion=60s` el contexto arranca (el formato simple se acepta). Si `60s` no se aceptara, usar `PT60S` como valor por defecto y anotarlo en el design. Verificar con la prueba.

## 3. Marcación una sola vez

- [x] 3.1 Agregar `marcacionHecha` (boolean, falso por defecto) a `CancelacionEvento` con su getter; `registrarMarcacion` la pone en verdadero solo cuando la marcación tuvo éxito (separar en dos consultas o agregar un parámetro); `procesar` pide la marcación solo si es falsa. Prueba de repositorio en `CancelacionEventoRepositoryTest`: la marca queda verdadera tras una marcación exitosa y falsa tras una fallida. Verificar con la prueba.
- [x] 3.2 Ajustar `unProcesamientoRepetidoConMarcacionEnCeroNoPideOtroReintegro` de `CancelacionEventosTest` (ahora la segunda corrida no pide la marcación: `entradas.marcaciones() == 1`) y agregar: con `entradas.setMarcacionCaida(true)` en el primer procesamiento, el retomado vuelve a pedirla y la marca queda verdadera. Verificar con `.\mvnw test -Dtest=CancelacionEventosTest`.

## 4. Documentación

- [x] 4.1 Reemplazar "el próximo aviso la retoma" / "con un aviso repetido" por la reanudación automática en: `compras/src/main/resources/static/openapi.yaml` (descripción de `POST /eventos-cancelados`, incluidos el punto de Pagos sin respuesta y el párrafo de los 5 minutos), comentarios de `CancelacionEvento`, `CancelacionService` y `application.properties`. Verificar con `grep -rn "próximo aviso\|aviso repetido la retome"` sobre `compras/src` (sin resultados) y `.\mvnw test -Dtest=DocumentacionOpenApiTest`.
- [x] 4.2 `compras/README.md`: agregar a la tabla `compras.cancelacion.intervalo-reanudacion` (`60s`) y `compras.cancelacion.reanudacion-activa` (`true`), y ajustar la fila de `plazo-en-proceso` ("tiempo tras el cual la revisión automática retoma una cancelación interrumpida"). Verificar releyendo la tabla.
- [x] 4.3 `docs/historias-ms3.md`: en E-1, la viñeta de Pagos sin respuesta dice que la cancelación se retoma automáticamente; agregar una viñeta "Dado una cancelación que quedó en proceso porque el servicio se cayó, cuando pasa el plazo, entonces se retoma sola sin esperar otro aviso"; actualizar la fila "Cancelación de un evento" de "Resueltos" con la reanudación automática. Verificar con `grep -n "aviso repetido" docs/historias-ms3.md` (solo los casos de aviso repetido que siguen valiendo).

## 5. Verificación integral

- [x] 5.1 Confirmar que el `## Purpose` de `openspec/specs/cancelacion-de-eventos/spec.md` menciona las compras confirmadas y las pendientes ya cobradas (ya actualizado; no requiere cambios) y correr `openspec validate --specs --strict` y `openspec validate reanudar-cancelaciones --strict` sin errores.
- [x] 5.2 Correr `.\mvnw test` en `compras/` y confirmar que pasa toda la suite.

## Workflow follow-up

- Archivar el cambio con `/opsx:archive` cuando esté revisado (sincroniza `cancelacion-de-eventos`).
