# Seguimiento de pendientes de MS2

Fecha de revisión: 2026-11-09. Evidencia estática del repositorio; no acredita el estado de ninguna base persistente.

## H1 — restricciones documentadas sin cambiar validaciones Java

OpenAPI conserva las rutas, métodos y códigos HTTP. Los UUID de reserva/devolución tienen sintaxis hexadecimal 8-4-4-4-12, admitiendo mayúsculas y minúsculas; no se exige una versión UUID específica. Los IDs externos mantienen comparación exacta conforme V7.

Se distinguen deliberadamente las validaciones reales: eventoId se valida como no blanco y longitud máxima de 255 unidades UTF-16 en inicialización/creación, mientras consulta y venta buscan la identidad sin ejecutar ese mismo validador. sectorId tiene @NotBlank y @Size(max=255) en inicialización y devoluciones; en creación, el DTO tiene @NotBlank sin @Size y el servicio comprueba pertenencia al sector existente. No se inventa un máximo de validación DTO compartido entre esos casos.

clienteId (header X-User-Id) y solicitudId tienen longitud de 1 a 255 puntos de código Unicode; Java no les aplica trim ni rechazo general de cadenas compuestas por espacios. Esta descripción no sustituye la autenticación del Gateway. Para longitudes UTF-16 se documenta la unidad y la extensión x-java-max-utf16-length, porque maxLength de JSON Schema cuenta puntos de código.

fila admite todo el rango Integer, incluidos cero y negativos; numero y cantidad deben ser positivos. Se documentan int32 y sus máximos, sin crear una regla positiva para fila. Las variantes siguen cerradas a propiedades adicionales y no aceptan campos de la otra variante, ni siquiera con null. Ningún validador Java se modifica por H1.

## Gateway — tarea 5.3 abierta

`Backend/APIGateWay/api.java` contiene únicamente un comentario. No hay aplicación, configuración de rutas, mecanismo de autenticación ni pruebas de Gateway. El historial incluye el commit `8fa45b9` (apigateway), pero el contenido actual no permite implementar o verificar una integración real.

Configuración requerida al responsable del Gateway:

- Publicar únicamente `POST /disponibilidad/eventos/{eventoId}/reservas`, preservando método y path completos hacia MS2. No usar una regla pública general `/disponibilidad/**`.
- No publicar PUT de inicialización/venta, GET de disponibilidad/reserva, ni POST de confirmación/liberación. Comprobar también rechazo por método, no solamente por prefijo.
- Obtener la identidad del cliente del contexto autenticado; sustituir un `X-User-Id` aportado por el consumidor en lugar de confiar en él o concatenarlo. MS2 usa ese header, nunca un titular del body.
- Propagar `X-User-Role` con su valor canónico (`CLIENTE`, `ORGANIZADOR` o `ADMIN`) al endpoint público de MS2 junto con `X-User-Id`. MS2 no consume ni autoriza por `X-User-Role`; el Gateway debe autorizar según su política aprobada y no aceptar identidad/rol falsificados desde el cliente.
- Conservar los roles canónicos `CLIENTE`, `ORGANIZADOR`, `ADMIN`; no inventar alias. Falta la configuración real de autenticación y la matriz de permisos del Gateway, incluida la eventual actuación de ORGANIZADOR/ADMIN como clientes; no se decide aquí.
- Las comunicaciones MS1/MS3 → MS2 y MS2 → MS1 permanecen REST/JSON directas, fuera del Gateway. La URL pública del Gateway no está proporcionada.

Pruebas de aceptación pendientes: única ruta/método público; identidad autenticada frente a header falsificado; path sin reescritura; roles canónicos y política aprobada; prohibición de todas las rutas internas incluso con prefijos similares. Las pruebas HTTP de MS2 no sustituyen estas pruebas de Gateway. 5.3 no está completada.

## Compensaciones — tarea 6.2 abierta

No existe en este checkout `Backend/mcsv-compras-devoluciones` ni código/contrato verificable de MS3. `Backend/mcvs-pagos/Mati.java` contiene solamente comentarios, sin implementación verificable de MS4. Sí está disponible `Backend/msvc-entradas/openapi-entradas.yaml` (MS5), que describe invalidación/reintegro de entradas, pero no coordina con MS2 ni MS4. MS2 conserva sus operaciones locales idempotentes; no asume pagos, compras, emisión ni invalidación de tickets.

| Escenario | Contrato/responsabilidad existente | Recuperación que puede afirmarse y pendiente |
|---|---|---|
| Reserva creada, falla la compra | MS2 mantiene una reserva temporal de 10 minutos | La liberación automática recupera inventario posteriormente. Falta decidir/documentar si MS3 solicita una liberación anticipada y con qué motivo aprobado; no equiparar cualquier fallo de compra a PAGO_RECHAZADO. |
| Pago aprobado, falla confirmación | MS3 llama directamente a confirmación; MS2 rechaza vencida/liberada y no contabiliza dos veces una confirmada | Ante respuesta perdida, repetir confirmación es idempotente si ya fue confirmada; consultar internamente permite reconciliar. Ante rechazo definitivo MS3 coordina con MS4. Faltan contrato de reintegro, persistencia durable del pendiente, política de recuperación y tratamiento de resultados inciertos. No repetir cobros. |
| Pago rechazado, falla liberación | PAGO_RECHAZADO libera la reserva temporal completa, nunca una confirmada | Repetir esa operación no duplica efectos; el scheduler es una recuperación eventual adicional. MS3 debe definir/persistir el pendiente y su política de reintento. |
| Devolución aprobada, falla liberación | Secuencia acordada: MS3 invalida en MS5, libera detalles en MS2, luego solicita reintegro a MS4 | Repetir exactamente el UUID global devolucionId, reserva y detalles devuelve 204; otra reserva/detalles produce 409. Conservar esa identidad y contenido. Falta el mecanismo durable de MS3, manejo de 409/errores definitivos, respuestas perdidas y recuperación entre invalidación, liberación y reintegro. No generar un UUID nuevo por reintento. |

Un timeout no demuestra que una operación no se haya confirmado. El contrato MS5 disponible usa idempotencia por entrada/compra; MS2 usa `devolucionId` global y MS4 carece de contrato verificable en este checkout. No se evidencia una clave común de saga ni persistencia durable de compensación entre esos servicios. No se inventan endpoints, motivos de liberación, plazos ni una saga nueva. El Outbox de MS2 notifica disponibilidad a MS1; no coordina compensaciones MS3/MS4/MS5. 6.2 requiere que sus responsables acuerden identidad durable de operación, reintentos/reconciliación ante resultado incierto y secuencia ante fallo parcial, y aporten evidencia de integración.

## Inventario operativo — tarea 1.1 abierta por entorno

| Entorno | Valores conocidos | Evidencia y estado |
|---|---|---|
| Local de desarrollo | MS1 `http://localhost:8081`; MS2 `http://localhost:8080`; MS3 `http://localhost:8083`. JDBC local documentado `localhost:3307/disponibilidad_reservas` | Configuración y contratos inspeccionados; no se inició ni consultó la base local. El usuario informó el despliegue V1–V7 y validación previa; esta revisión no lo revalida. Falta adjuntar al inventario operativo la evidencia vigente de historial, checksum de respaldo y validación por entorno. |
| Pruebas H2 | Bases en memoria definidas por cada suite | Verifican lógica/HTTP y rollback; no equivalen a probar bloqueos y collations MySQL. |
| Pruebas MySQL descartables | Contenedor nuevo, puerto local efímero y esquemas aleatorios | Usar exclusivamente el procedimiento de pruebas siguiente. La evidencia de cada ejecución no acredita un despliegue persistente. |
| Otros entornos | No hay destinos ni infraestructura proporcionados | Pendientes propietario, URL/puerto, versión MySQL, revisión desplegada, historial Flyway, responsable del respaldo y evidencia de Hibernate validate. No se inventan valores de producción. |

La configuración real de MS2 está en `src/main/resources/application.properties`. El procedimiento histórico de V7 está en [despliegue-v7.md](despliegue-v7.md); no representa una autorización para ejecutar migraciones ni prueba del estado actual. No se marca 1.1 completa por verificar esquemas temporales.

## Pruebas MySQL aisladas

Las tres suites antiguas habilitadas con `-Dms2.mysql.integration=true` ahora exigen `MS2_TEST_MYSQL_URL` y `MS2_TEST_MYSQL_PASSWORD`; no leen ni usan las credenciales de application.properties. La URL aceptada es `jdbc:mysql://127.0.0.1:<puerto-efimero>/ms2_disposable`, excluyendo 3307, en una instancia creada exclusivamente para la ejecución. Las suites crean y eliminan únicamente sus esquemas aleatorios en ese servidor descartable y aplican las migraciones existentes según el escenario (incluidas pruebas de evolución desde esquemas anteriores).

Con el contenedor nuevo ya disponible y las variables suministradas al proceso sin registrar contraseñas en comandos o archivos:

```sh
./mvnw -Dms2.mysql.integration=true -Dtest=FilaIntegerMySqlMigrationTests,DisponibilidadMySqlConcurrencyTests,DisponibilidadMySqlLegacyBackfillTests test
```

El marcador del nombre y el puerto bloquean accidentes habituales, pero no demuestran por sí solos que el servidor sea descartable: el ejecutor debe verificar que creó una instancia nueva sin volúmenes existentes. Las pruebas R2 tienen su propio opt-in `MS2_R2_MYSQL_URL`/`MS2_R2_MYSQL_PASSWORD`. No redirigir ninguno de estos mecanismos a una instancia de trabajo. Eliminar únicamente el contenedor/volúmenes creados para la ejecución al terminar.

## R3 — decisión funcional pendiente, sin cambio de implementación

La reserva vence lógicamente al alcanzar `expiraEn`; desde ese instante no puede confirmarse aunque permanezca almacenada como VIGENTE. La duración sigue siendo 10 minutos.

La liberación física ocurre al confirmar la transacción del barrido automático o una liberación permitida. El scheduler usa fixedDelay predeterminado de 60000 ms entre el final de una ejecución y el comienzo de la siguiente; no es una garantía de liberación en 60 segundos. Demoras, duración del lote, errores o aplicación detenida pueden ampliar el desfase.

Hasta la liberación física, asientos y cantidades continúan ocupados y el contador reservado puede consumir el límite del cliente. La creación de reservas no ejecuta limpieza oportunista. El barrido vuelve a seleccionar reservas vencidas pendientes tras fallos/reinicio; no modifica ventas confirmadas.

El equipo debe decidir el máximo aceptable desde `expiraEn` hasta que inventario y límite del cliente vuelvan a estar disponibles, considerando barrido de 60000 ms, tamaño del lote, errores y períodos sin servicio. Si ese plazo excede lo aceptado, debe elegir el comportamiento funcional requerido antes de modificar implementación. No se modifica el intervalo ni se agrega liberación inmediata.

## Evidencia de la ejecución de pruebas anterior

Compilación Maven correcta. Suite combinada: **242 pruebas aprobadas** (180 en la suite habitual, 49 MySQL de migraciones/concurrencia/backfill y 13 MySQL de R2), sin fallos ni errores. La ejecución habitual reportó además 56 pruebas omitidas por ser suites MySQL opt-in, que se ejecutaron por separado. Incluye las 49 MySQL antiguas (5 de migración, 43 de concurrencia y 1 de backfill) y las 13 de R2 en MySQL. Se usó una instancia nueva MySQL **8.4.13**, sin volúmenes preexistentes, con puerto efímero diferente de 3307; el contenedor y su volumen temporal se eliminaron al finalizar. Los informes detallados están en `target/surefire-reports/` y son artefactos de ejecución, no evidencia de despliegue persistente ni de integración real con Gateway/MS3/MS4.

OpenSpec: validación estricta de `implementar-ms2-disponibilidad-reservas` aprobada. Las pruebas verifican las implementaciones y contratos locales disponibles; no existe una suite de contratos ejecutables del Gateway o de compensaciones entre los servicios ausentes.

OpenAPI 3.1 validado con Redocly CLI (perfil minimal), sin errores y con 8 advertencias: servidor localhost y ausencia de declaraciones security en las siete operaciones. No se inventó una URL productiva ni un esquema de autenticación para silenciarlas. Se verificaron además 12 casos JSON Schema de variantes de reserva/devolución (válidos, null de otra variante e incompletos) con Ajv 2020, todos con el resultado esperado. `git diff --check` sin errores.

## Segunda revisión técnica — 2026-11-09

Esta revisión fue documental/estática; no repitió pruebas ni consultó bases de datos. En `docs/openapi/ms2.yaml` se corrigió el alcance del parámetro `eventoId` para distinguir entradas validadas de búsquedas exactas, se reflejó `@NotBlank` en los `sectorId` de entrada y se retiraron restricciones de no-blanco que no estaban garantizadas para datos históricos de respuesta. La validación Java y las rutas no cambiaron.

Las cifras 198 y 201 de `planificacion-tecnica-ms2.md` son resultados históricos fechados; el resumen consolidado de 242 ejecuciones de pruebas previa se aclaró para separar las 180 pruebas habituales de las 62 ejecuciones MySQL opt-in y las 56 omisiones de la invocación habitual. Esas cifras no describen una ejecución de esta revisión.
