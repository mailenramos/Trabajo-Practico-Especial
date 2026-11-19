# Diseño: token de servicio para outbox

## Contexto

`RestClientOutboxHttpClient` toma el destino y payload de cada aviso y envía `X-Aviso-Id`, sin `X-Service-Token`. `OutboxDispatcher` usa el mismo cliente para todos los tipos y clasifica `403` como error definitivo. `PreferenciasProperties` solo configura `baseUrl`; el token no pertenece exclusivamente a Preferencias, por lo que no se incorpora allí. La búsqueda en el código actual de MS1 no encontró validación entrante de `X-Service-Token`: no se altera ni se presupone esa protección en este cambio.

## Objetivos y exclusiones

- Configurar un token compartido para todas las llamadas salientes de outbox, sin persistirlo ni mostrarlo en logs.
- Evitar que una configuración vacía envíe solicitudes que el destino rechazaría definitivamente.
- Mantener la clasificación HTTP, los límites de reintento, la idempotencia y las transacciones existentes.
- Fuera de alcance: autenticación entrante, llamadas que no usan outbox, cambios a otros servicios o exposición de rutas internas.

## Decisiones

1. `application.yml` declara `servicio.token: ${SERVICIO_TOKEN:}`. Se usa una propiedad común del servicio y se inyecta en el cliente de outbox, no en `PreferenciasProperties` ni en cada aviso. Compose pasa `SERVICIO_TOKEN: ${SERVICIO_TOKEN}` a `lugares`; `.env` permanece ignorado. El valor real se aporta externamente y no se incluye en ejemplos, logs ni documentos.
2. Token vacío o solo espacios se considera ausente; un token no vacío se envía sin modificar. Si está activo `docker`, una validación de configuración aborta el arranque con `SERVICIO_TOKEN no configurada`, incluso si hay otros perfiles activos. En otros perfiles se permite el arranque y se emite un `WARN` sin el valor.
3. En perfiles no Docker y sin token, el scheduler no ejecuta el barrido ni reclama avisos, y la entrega inmediata y `procesarSiguiente` tampoco reclaman. No se hace HTTP ni se cambia estado, intentos o `ultimoError`; los pendientes esperan a que se configure el token. Se emite un solo `WARN` al arrancar, no en cada pasada. Con token, se reanuda el procesamiento normal; un `403` real sigue siendo definitivo.
4. Con token, cada POST conserva el destino, payload y `X-Aviso-Id` y agrega `X-Service-Token`. Los mensajes, excepciones, `ultimoError` y logs del camino de entrega no deben contener el valor del token; evitar volcar headers, configuración o respuestas sensibles. La llamada sigue fuera de la transacción y no altera el éxito de la mutación ya confirmada.
5. El stub `postman/preferencias-stub.js` validará opcionalmente el header solo cuando `SERVICIO_TOKEN` esté definido y no vacío; ante ausencia o discrepancia responderá `403` sin revelar el valor. Sin token configurado en el stub, mantendrá el comportamiento actual. El README explicará cómo aportar el mismo token a MS1 y al stub sin escribir el valor en el repositorio.

## Riesgos y verificación

- Un token mal configurado pero no vacío puede recibir `403` y dejar el aviso en `ERROR_FINAL`; se conserva deliberadamente la política HTTP existente.
- Un token ausente en otros perfiles pausa la entrega: documentar que los avisos esperan sin consumir intentos y que se reanudan al configurar el token.
- Verificar header y estabilidad de ID en entregas/reentregas de varios tipos; ausencia de HTTP con token vacío; WARN sin secreto; fallo de arranque Docker; y ausencia del token en logs y `ultimoError`. Las pruebas de integración de publicación configuran un token de prueba y conservan sus aserciones de entrega.
- Validar ambos Compose, OpenSpec estricto y la suite Maven completa. No se modifica la validación entrante ni se afirma que exista.
