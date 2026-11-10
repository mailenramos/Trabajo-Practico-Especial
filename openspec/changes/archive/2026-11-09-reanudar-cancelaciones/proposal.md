## Why

Eventos reintenta `POST /eventos-cancelados` cada 60 s hasta recibir 200 o 202 y, una vez que recibe 202, no lo vuelve a enviar. Hoy una cancelación que queda `EN_PROCESO` más allá de `compras.cancelacion.plazo-en-proceso` (porque el servicio se cayó a mitad del procesamiento o una compra tuvo una falla) solo se retoma si llega otro aviso, que ya no va a llegar: la cancelación queda colgada y sus compras sin reintegro.

## What Changes

- Una tarea programada revisa cada `compras.cancelacion.intervalo-reanudacion` (60 s por defecto) las cancelaciones `EN_PROCESO` tomadas hace más de `compras.cancelacion.plazo-en-proceso` y las retoma con la lógica de retoma que ya existe, sin duplicar reintegros.
- La toma de una cancelación sigue siendo la actualización condicional atómica actual (`tomadaEn` leído = `tomadaEn` en la base): entre dos instancias, o entre la tarea y un aviso repetido, solo una la procesa.
- La marcación de las entradas del evento en Entradas se pide una sola vez: si ya se hizo con éxito, un procesamiento retomado no la vuelve a pedir; si había fallado, se reintenta.
- Propiedades nuevas: `compras.cancelacion.intervalo-reanudacion` (60s) y `compras.cancelacion.reanudacion-activa` (true). Con `false` la tarea no se registra. En las pruebas está desactivada y se prueba el método de reanudación directamente.
- Un aviso repetido de una cancelación vencida la sigue retomando, con la misma toma atómica.
- Documentación: `openapi.yaml`, `README.md`, `application.properties`, `docs/historias-ms3.md` y comentarios del código dejan de decir que "el próximo aviso la retoma" y dicen que se retoma automáticamente.

**Verificado en el código:** el registro de la cancelación se guarda (y se confirma en la base) antes de responder 202. `CancelacionService.recibir` hace `cancelaciones.saveAndFlush(...)` fuera de cualquier transacción propia, así que el `INSERT` se confirma en ese momento; recién después lanza el procesamiento y devuelve `ACEPTADO`, que el controlador traduce a 202. Por eso, si el servicio se cae después del 202, la cancelación queda en la base `EN_PROCESO` y la tarea la encuentra.

**Ya hecho:** el `## Purpose` de `openspec/specs/cancelacion-de-eventos/spec.md` ya dice que cubre las compras confirmadas y las pendientes ya cobradas (se actualizó a mano antes de este cambio). Solo queda correr `openspec validate --specs --strict` al terminar.

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `cancelacion-de-eventos`: las cancelaciones interrumpidas se retoman automáticamente, sin depender de otro aviso; la marcación de Entradas no se repite si ya se hizo; la compra pendiente que no se pudo cerrar porque Pagos no respondió se retoma automáticamente.

## Impact

- **Servicios externos:** Entradas (la marcación por evento deja de repetirse en un procesamiento retomado) y Eventos (deja de necesitar reenviar el aviso). Sin cambios de contrato.
- **Código:** `CancelacionService` (método de reanudación y toma compartida con `recibir`), `CancelacionEventoRepository` (búsqueda de vencidas), `CancelacionEvento` (marca de marcación hecha), configuración de la tarea programada (`@EnableScheduling` condicionado por propiedad), `application.properties`.
- **Base de datos:** una columna nueva en `cancelacion_evento` (marcación hecha); `ddl-auto=update` la agrega con valor falso en las filas existentes.
- **Pruebas:** propiedad de desactivación en todas las clases `@SpringBootTest`; pruebas de reanudación, concurrencia y configuración.
- **Documentación:** `compras/src/main/resources/static/openapi.yaml`, `compras/README.md`, `docs/historias-ms3.md`.

## Fuera de alcance

- Un "latido" que renueve `tomadaEn` mientras una cancelación se procesa: un procesamiento vivo que dure más que el plazo puede ser retomado en paralelo (ya pasa hoy con un aviso repetido); las protecciones por compra evitan duplicar reintegros.
- Reintentar automáticamente reintegros `FALLIDO`: siguen esperando a un administrador.
- Cambios en el contrato con Eventos o en la respuesta del aviso.
