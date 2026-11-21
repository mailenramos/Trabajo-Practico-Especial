# F5 · Gestión de entradas — Entrega 2 (User Stories)

> **Estado:** implementado en MS5 (ver `Backend/msvc-entradas/openspec/changes/validacion-qr-entrega2`); los tests de concurrencia con PostgreSQL requieren Docker. La numeración (`US-5.x`) es provisoria hasta confirmar con el grupo el número de feature y si continúa la numeración de la Entrega 1. Los puntos marcados como *a confirmar* dependen de respuestas de otros integrantes (ver el final del documento).

En la Entrega 1, Gestión de entradas emite las entradas de una compra confirmada, guarda un código QR (un UUID aleatorio) por entrada, permite consultarlas y gestiona los estados de reintegro y devolución. No genera una imagen QR ni registra su uso. En la Entrega 2 se incorporan la **validación real del QR y el registro del ingreso** (estado `UTILIZADA`), la **autorización del personal de acceso por evento**, el **cierre persistente del evento** al cancelarse, la **consulta de entradas por compra** para recuperar resultados inciertos, y la adaptación de las consultas a JWT y la protección de las rutas internas.

**Servicios:** Gestión de entradas (MS5). Interactúa con Lugares, sectores y eventos (MS1: asignaciones de personal), Compras y devoluciones (MS3: emisión, devolución y cancelación), Usuarios (MS6, a través del JWT) y el API Gateway.

**Estados de una entrada:**

```
             validación QR
  VIGENTE ─────────────────► UTILIZADA        (final)
     │
     │ devolución aprobada / evento cancelado
     ▼
  EN_REINTEGRO ────────────► DEVUELTA         (final)
              reintegro completado
```

Solo una de las dos transiciones que salen de `VIGENTE` puede completarse. No hay transiciones hacia atrás: una entrada `EN_REINTEGRO` nunca vuelve automáticamente a `VIGENTE`.

---

## US-5.1 · Validar el QR de una entrada al ingresar

Como personal de acceso, quiero escanear el código QR de una entrada en el ingreso de un evento, para dejar pasar solo a quienes tienen una entrada válida para ese evento y registrar su ingreso.

* Dado un QR de una entrada `VIGENTE` del evento indicado y un usuario autorizado (US-5.3), cuando valida el QR (`POST /eventos/{eventoId}/ingresos` con `{codigoQr}`; *ruta a confirmar*), entonces la entrada pasa atómicamente de `VIGENTE` a `UTILIZADA` y la respuesta incluye el sector, la fila y el número para ubicar al asistente.
* Dado un ingreso validado, cuando se registra, entonces se guardan la entrada, el evento, la fecha y hora (UTC) y el identificador del usuario que validó, tomado de `X-User-Id`.
* Dado un QR que no corresponde a ninguna entrada, cuando se valida, entonces se rechaza con «no encontrado» (`ENTRADA_NO_ENCONTRADA`).
* Dado un QR de una entrada de otro evento, cuando se valida para el evento indicado, entonces se rechaza (`ENTRADA_DE_OTRO_EVENTO`) y la entrada no cambia de estado.
* Dado una entrada ya `UTILIZADA`, cuando se vuelve a validar, entonces se rechaza con un conflicto (`ENTRADA_YA_UTILIZADA`) que informa la fecha y hora del ingreso anterior.
* Dado una entrada `EN_REINTEGRO` o `DEVUELTA`, cuando se valida, entonces se rechaza con un conflicto (`ENTRADA_NO_HABILITADA`) y la entrada no cambia de estado.
* Dado un evento cerrado (US-5.5), cuando se valida cualquier entrada de ese evento, entonces se rechaza (`EVENTO_CERRADO`).
* Dado un pedido con un `codigoQr` faltante o con formato inválido, cuando se procesa, entonces se responde con un error de validación.
* Dado una entrada `UTILIZADA`, cuando se intenta emitir otra entrada para el mismo asiento numerado del evento, entonces se rechaza: el asiento sigue ocupado, igual que con una entrada `VIGENTE`.

## US-5.2 · Impedir que una entrada se use dos veces

Como organizador, quiero que una misma entrada no pueda validarse dos veces aunque se escanee al mismo tiempo en dos accesos, para evitar ingresos duplicados.

* Dado dos o más validaciones simultáneas del mismo QR, cuando se procesan, entonces exactamente una tiene éxito y las demás reciben `ENTRADA_YA_UTILIZADA`.
* Dado validaciones simultáneas del mismo QR, cuando terminan, entonces existe un único registro de ingreso para esa entrada: la base tiene una restricción de unicidad por entrada.
* Dado la validación, cuando se ejecuta, entonces el cambio de estado es una actualización condicional (`VIGENTE` → `UTILIZADA` solo si sigue `VIGENTE`) dentro de la misma transacción que registra el ingreso.
* Dado el código, cuando se ejecutan las pruebas, entonces hay pruebas de integración con PostgreSQL que validan concurrentemente la misma entrada y verifican un único éxito.

## US-5.3 · Autorizar solo al personal asignado al evento

Como administrador, quiero que el personal de acceso solo pueda validar entradas de los eventos a los que está asignado, para no darle permisos generales sobre todos los eventos.

* Dado un pedido de validación, cuando llega a Gestión de entradas, entonces el usuario y sus roles se toman de `X-User-Id` y `X-User-Roles`, propagados por el API Gateway desde el JWT validado.
* Dado un usuario sin los roles `PERSONAL_ACCESO` ni `ADMIN`, cuando intenta validar, entonces recibe acceso denegado y no se consulta la entrada.
* Dado un usuario con `PERSONAL_ACCESO`, cuando valida, entonces Gestión de entradas consulta a Lugares, sectores y eventos (`GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion`) en ese momento, sin usar datos guardados de consultas anteriores, y envía `X-Service-Token` y `X-Correlation-Id`.
* Dado que esa consulta responde `autorizado: false`, porque el usuario no tiene una asignación activa o porque el evento no existe, cuando se valida, entonces se deniega el acceso (`PERSONAL_NO_AUTORIZADO`) y la entrada no cambia de estado.
* Dado que `autorizado: true` solo indica una asignación activa, cuando se valida, entonces Gestión de entradas comprueba por su cuenta el rol `PERSONAL_ACCESO` y que el evento no esté cerrado.
* Dado una asignación revocada, cuando el personal intenta una validación cuya comprobación de autorización ocurre después de confirmada la revocación, entonces se deniega.
* Dado una validación que ya obtuvo la autorización, cuando se revoca la asignación mientras se procesa, entonces la validación puede completarse (no hay coordinación distribuida para deshacerla).
* Dado que Lugares, sectores y eventos no responde, responde con un 4xx o 5xx o excede el tiempo de espera, cuando se valida, entonces la validación se rechaza temporalmente (`AUTORIZACION_NO_DISPONIBLE`, HTTP 503) y la entrada no cambia de estado.
* Dado un usuario con rol `ADMIN`, cuando valida, entonces no se exige asignación al evento (validación excepcional) y el ingreso queda registrado como excepcional.
* Dado la comprobación de autorización, cuando se ordena respecto de la búsqueda de la entrada, entonces se hace primero, para que un usuario no autorizado no pueda averiguar si un QR existe.

## US-5.4 · Resolver la competencia entre ingreso y devolución

Como cliente y como organizador, queremos que una entrada no pueda usarse para ingresar y a la vez devolverse, para que no haya ingresos con entradas reintegradas ni reintegros de entradas usadas.

* Dado una entrada `VIGENTE`, cuando Compras y devoluciones aprueba su devolución, entonces Gestión de entradas la bloquea con una transición atómica `VIGENTE` → `EN_REINTEGRO` y, desde ese momento, su QR no permite ingresar.
* Dado una validación QR y un bloqueo por devolución simultáneos sobre la misma entrada, cuando se procesan, entonces solo uno se completa: la entrada termina `UTILIZADA` o `EN_REINTEGRO`, nunca en ambos.
* Dado una entrada `UTILIZADA`, cuando se intenta pasarla a `EN_REINTEGRO`, entonces se rechaza con `409 ENTRADA_UTILIZADA` y la entrada no cambia. Compras y devoluciones deja la solicitud `APROBADA` con el error guardado para que la vea el administrador, y no mueve dinero.
* Dado una entrada `UTILIZADA`, cuando Compras y devoluciones la consulta al recibir una solicitud de devolución, entonces la ve en estado `UTILIZADA` y rechaza la solicitud con `409 ENTRADA_NO_VIGENTE`.
* Dado una entrada `EN_REINTEGRO`, cuando se valida su QR, entonces se rechaza (US-5.1).
* Dado una entrada ya `EN_REINTEGRO`, cuando se repite el pedido de bloqueo, entonces responde con éxito sin cambios (idempotente, como en la Entrega 1).

## US-5.5 · Cerrar el evento al cancelarse

Como administrador, quiero que al cancelar un evento Gestión de entradas lo cierre de forma permanente, para que no se emitan ni se validen entradas de un evento cancelado.

* Dado la cancelación de un evento, cuando Compras y devoluciones pide el cierre (`POST /internal/eventos/{eventoId}/entradas/reintegro`, la misma ruta de la Entrega 1), entonces Gestión de entradas registra el cierre de forma persistente y pasa a `EN_REINTEGRO` las entradas `VIGENTE` del evento, en la misma transacción.
* Dado un evento sin entradas emitidas, cuando se pide su cierre, entonces el cierre igual se registra y se confirma.
* Dado un evento ya cerrado, cuando se repite el pedido, entonces responde con éxito, confirma que el evento está cerrado y no modifica nada (idempotente).
* Dado un evento cerrado, cuando llega una emisión nueva para ese evento, entonces se rechaza con `409 EVENTO_CERRADO` y no se crea ninguna entrada. Compras y devoluciones trata esa compra como la de un evento cancelado y pide el reintegro automático.
* Dado un evento cerrado, cuando se valida un QR de ese evento, entonces se rechaza (`EVENTO_CERRADO`).
* Dado una emisión o una validación en curso y un pedido de cierre simultáneos, cuando se procesan, entonces se coordinan con un bloqueo local por evento: la operación que se completó antes del cierre queda registrada y es identificable, y la que llega después se rechaza.
* Dado la respuesta del cierre, cuando se devuelve, entonces informa `{idEvento, entradasMarcadas, cerrado: true}`. Compras y devoluciones no da por terminada la cancelación hasta recibir `cerrado: true`; si falta o viene en `false`, reintenta.
* Dado entradas `UTILIZADA` de un evento que se cancela, cuando se cierra el evento, entonces conservan su estado (*a confirmar con el grupo*).
* Dado una compra que ya fue emitida antes del cierre, cuando se repite su emisión con los mismos datos después del cierre, entonces devuelve las entradas existentes sin crear nuevas (la idempotencia por `idCompra` se mantiene).

## US-5.6 · Consultar las entradas de una compra

Como Compras y devoluciones, quiero consultar las entradas emitidas para una compra y sus estados sin emitir nada, para recuperar los resultados inciertos antes de terminar la cancelación de un evento.

* Dado una compra con entradas emitidas, cuando se consulta (`GET /internal/compras/{idCompra}/entradas`), entonces se devuelven todas sus entradas con su estado, sector, fila, número y código QR.
* Dado una compra sin entradas emitidas, cuando se consulta, entonces se devuelve una lista vacía (no es un error), que significa «no se emitió», y no se emite ninguna entrada.
* Dado una emisión cuyo resultado es incierto (por ejemplo, por un timeout), cuando Compras y devoluciones quiere reintentarla, entonces puede consultar primero esta ruta para confirmar si ya se emitió.
* Dado la consulta, cuando se repite, entonces siempre devuelve el estado actual y nunca modifica datos.

## US-5.7 · Consultar mis entradas con el token (actualiza la Entrega 1)

Como cliente, quiero consultar mis entradas usando mi sesión, para ver sus estados y mostrar el QR en el ingreso sin declarar mi identidad.

* Dado un cliente autenticado, cuando lista sus entradas (`GET /entradas`) o consulta una (`GET /entradas/{id}`), entonces el usuario se toma de `X-User-Id` y solo ve sus propias entradas, con los filtros y la paginación de la Entrega 1.
* Dado un cliente, cuando consulta una entrada de otro cliente, entonces recibe acceso denegado.
* Dado un pedido que informa un identificador de usuario en parámetros, cuando se procesa, entonces se ignora: el usuario se toma siempre de `X-User-Id`.
* Dado un administrador, cuando lista o consulta entradas, entonces puede ver las de cualquier cliente y filtrar por cliente, evento y estado.
* Dado un usuario con solo el rol `PERSONAL_ACCESO`, cuando lista o consulta entradas, entonces recibe acceso denegado: su operación es la validación (US-5.1).
* Dado un usuario con varios roles, cuando consulta, entonces se aplican los permisos del conjunto de sus roles, sin elegir uno arbitrariamente.
* Dado un pedido sin `X-User-Id`, cuando llega al servicio, entonces recibe falta de autenticación.

## US-5.8 · Proteger las operaciones internas

Como responsable de la plataforma, quiero que solo los microservicios autorizados puedan emitir entradas, cambiar sus estados y cerrar eventos, para que nadie pueda alterar entradas desde afuera.

* Dado un endpoint interno (`/internal/...`), cuando se invoca desde afuera a través del API Gateway, entonces responde 404 porque no se enruta.
* Dado un pedido a un endpoint interno sin la credencial de servicio (`X-Service-Token`, según la propuesta de arquitectura; *a confirmar si se valida al recibir*) o con una incorrecta, cuando llega a Gestión de entradas, entonces se rechaza con falta de autenticación y no se ejecuta la operación.
* Dado la credencial de servicio, cuando se configura, entonces se toma de una variable de entorno y nunca se escribe en el log.
* Dado una llamada de Gestión de entradas a otro microservicio, cuando se envía, entonces incluye `X-Service-Token` y `X-Correlation-Id` con el identificador de correlación del pedido original, para seguir los logs entre servicios.

## US-5.9 · Mantener la trazabilidad de las entradas en reintegro

Como administrador, quiero que las entradas cuyo reintegro falló o quedó incierto permanezcan bloqueadas y consultables, para darles seguimiento sin que vuelvan a servir para ingresar.

* Dado una entrada `EN_REINTEGRO`, cuando Compras y devoluciones informa que el reintegro se completó, entonces pasa a `DEVUELTA` (idempotente, como en la Entrega 1).
* Dado una entrada `EN_REINTEGRO` cuyo reintegro falló o es incierto, cuando pasa el tiempo, entonces permanece `EN_REINTEGRO`: Gestión de entradas nunca la restaura automáticamente a `VIGENTE`.
* Dado una entrada `EN_REINTEGRO`, cuando Compras y devoluciones la consulta (por entrada o por compra, US-5.6), entonces obtiene su estado actual para darle seguimiento.
* Dado una entrada `VIGENTE` o `UTILIZADA`, cuando se intenta pasarla directamente a `DEVUELTA`, entonces se rechaza con un conflicto.

---

## Decisiones

1. **Sin ventana horaria:** una entrada se puede validar mientras esté `VIGENTE` y el evento no esté cerrado, sin restricción de fecha u hora.
2. **El QR es el UUID existente:** no se genera una imagen QR en esta entrega; el frontend o el personal puede generarla a partir del código. Lo nuevo es la validación y el consumo.
3. **No hay estado `CANCELADA`:** una entrada de un evento cancelado pasa a `EN_REINTEGRO`, y el cierre del evento impide validarla.
4. **Coordinación local por evento:** emisión y validación toman un bloqueo compartido sobre una fila de control del evento, y el cierre un bloqueo exclusivo; no hay transacciones distribuidas.
5. **Autorización en cada validación:** se consulta a MS1 siempre, sin caché, para que las revocaciones tengan efecto inmediato. Una falla de comunicación rechaza la validación temporalmente.

## Contratos acordados

| Con | Contrato | Estado |
|---|---|---|
| MS1 (Lugares, sectores y eventos) | `GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion` → siempre `200 {eventoId, usuarioId, autorizado}`. `autorizado: true` solo si hay una asignación activa y no revocada; un evento inexistente o sin asignación da `false`. Lee siempre el estado actual, sin caché. No mira el estado del evento ni el rol del usuario: esos controles los hace MS5. Un 404 o 5xx solo aparece ante una falla real, y MS5 lo trata como 503. MS5 envía `X-Service-Token` (MS1 todavía no lo valida) y `X-Correlation-Id`. | Acordado |
| MS3 (Compras y devoluciones) | **Estado `UTILIZADA`:** MS3 lo agrega a su enum y rechaza la solicitud de devolución con `409 ENTRADA_NO_VIGENTE`. **Bloqueo de una entrada utilizada:** `POST /internal/entradas/{id}/reintegro` → `409 ENTRADA_UTILIZADA`; MS3 deja la solicitud `APROBADA` con el error guardado y no mueve dinero. **Cierre:** `POST /internal/eventos/{idEvento}/entradas/reintegro` → `200 {idEvento, entradasMarcadas, cerrado: true}`, idempotente; MS3 reintenta hasta recibir `cerrado: true`. **Emisión tras el cierre:** `POST /internal/entradas/emision` → `409 EVENTO_CERRADO`; MS3 pide el reintegro automático de esa compra. **Consulta por compra:** `GET /internal/compras/{idCompra}/entradas` → lista (vacía = no se emitió). **Encabezados:** MS3 envía `X-Service-Token` y `X-Correlation-Id` (reenvía el del Gateway o genera uno). | Acordado |

## Supuestos y puntos a definir

| # | Tema | Propuesta | Con quién |
|---|---|---|---|
| 1 | Protección de `/internal/**` | Encabezado `X-Service-Token` (nombre de la propuesta de arquitectura) con un secreto compartido por variable de entorno. Falta definir si cada servicio lo valida al recibir: MS1 hoy no lo hace. | Grupo |
| 1b | Identificador de correlación | **Decidido:** `X-Correlation-Id` (el que usa MS1). El Gateway y MS6 ya lo usan. MS5 lo recibe del Gateway y lo reenvía en sus llamadas. Pagos (MS4) todavía usa `X-Request-Id`: lo adapta su responsable. | — |
| 2 | Numeración de las US | `F5`, `US-5.x`, formato de F12. | Grupo |
| 3 | Entradas `UTILIZADA` en un evento cancelado | Conservan su estado; no se reintegran. | Grupo |
| 4–7 | Estado `UTILIZADA`, error `ENTRADA_UTILIZADA`, ruta del cierre y consulta por compra | **Decidido** con MS3 (ver «Contratos acordados»). | — |
| 8 | Ruta pública de validación | `POST /eventos/{eventoId}/ingresos` con `{codigoQr}`, para `PERSONAL_ACCESO` y `ADMIN`, enrutada por el Gateway a MS5. | Gateway / frontend |
