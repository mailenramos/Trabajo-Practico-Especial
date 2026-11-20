# Diseño: trazabilidad y correlación

## Solicitudes HTTP

Un filtro servlet recibe `X-Correlation-Id`; solo acepta entre 1 y 100 caracteres ASCII de letras, números, guion, guion bajo y punto. Si falta, está vacío o no cumple ese formato, genera un UUID. Conserva el valor válido para la respuesta y la propagación, lo coloca en MDC bajo `correlationId` y devuelve el header también en respuestas de error. Al finalizar elimina esa clave del MDC para evitar contaminar la siguiente solicitud del mismo hilo, sin borrar claves ajenas.

Registrar un único log de acceso por solicitud al finalizar, excepto para `/actuator/**`, con método, ruta sin query string, `X-User-Id` solo si existe, código HTTP final y duración en milisegundos. La ruta no incluye el host ni los parámetros de consulta. La medición usa tiempo monotónico; los valores externos que se escriban en logs se tratan como datos, escapando caracteres de control para evitar inyección de líneas. El filtro cubre también rutas internas y errores; no debe duplicar el log por redispatch. Si se usa procesamiento asíncrono servlet, preservar el ID hasta su finalización y limpiar cada hilo utilizado.

El patrón de Logback incorpora `%X{correlationId}` y sigue escribiendo a stdout. No registrar headers completos, tokens de usuario o servicio, cuerpos ni contraseñas. Revisar los mensajes y excepciones de los clientes tocados para evitar que incluyan el query, headers, payloads o secretos; conservar errores útiles sin registrar su contenido sensible.

## Propagación HTTP

El cliente `HttpReservasClient` usa `RestClient` para inicialización, cambio de venta y consulta de disponibilidad. Centralizar la propagación en la configuración común de ese cliente, de manera que cada operación envíe `X-Correlation-Id` del contexto actual sin alterar timeouts, cuerpo ni manejo de errores. Si una operación originada en un scheduler no tiene contexto HTTP, crear un ID para esa operación y limpiarlo al terminar; no enviar un header vacío. El cliente MS6 aún no existe: cuando se implemente en `personal-acceso-eventos`, deberá usar el mismo mecanismo sin asumir un contrato o URL de MS6.

## Outbox

Agregar `correlationId` opcional al documento `AvisoOutbox`, separado del ID estable y del payload. Para publicación y cancelación, capturar el ID de la solicitud al crear el aviso dentro de la misma transacción de evento y outbox; no cambiar `X-Aviso-Id`, destinos, reintentos, estados ni la semántica de commit. Un aviso creado sin contexto HTTP, por ejemplo por backfill, puede quedar sin el campo.

Tanto la entrega inmediata como la programada usan el dispatcher. Por cada aviso reclamado, elegir el `correlationId` guardado; para un documento antiguo cuyo campo está ausente o vacío, generar un UUID nuevo al despacharlo. Usar ese ID como contexto de los logs del procesamiento y enviarlo en `X-Correlation-Id`, aun si el scheduler carece de MDC. El caso legacy genera un ID por despacho; no se exige migración ni persistencia retroactiva del ID. Los avisos nuevos conservan el mismo ID en sus reintentos.

Al finalizar cada despacho, restaurar el contexto previo del hilo, también ante excepciones, para que una entrega inmediata no reemplace el ID de la solicitud y el scheduler no herede el del aviso anterior. El dispatcher sigue pausado si falta `SERVICIO_TOKEN` y no cambia su clasificación de errores.

## Pruebas y documentación

Probar filtro, errores, ausencia/valor vacío, respuesta, limpieza y logs capturados; comprobar que no aparecen tokens, contraseñas, query ni cuerpos, incluidos errores de clientes. Verificar todas las operaciones de Reservas, envío inmediato, scheduler, reintento y aviso legacy sin ID, junto con el aislamiento del MDC entre avisos. Probar persistencia de publicación y cancelación con MongoDB de Testcontainers, sin usar MongoDB local compartido. Documentar el contrato y stdout en README.
