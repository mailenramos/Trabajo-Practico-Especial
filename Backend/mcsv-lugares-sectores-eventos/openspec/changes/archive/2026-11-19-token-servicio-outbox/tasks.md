# Tareas

- [x] 1. Configurar `servicio.token=${SERVICIO_TOKEN:}` y validación de arranque: perfil `docker` falla con `SERVICIO_TOKEN no configurada` si está vacío; otros perfiles arrancan y registran `WARN` sin el valor. Probar perfiles combinados y token vacío o solo espacios.
- [x] 2. Agregar `X-Service-Token` al cliente común de outbox conservando `X-Aviso-Id`, destino y payload; probar envíos y reentregas de más de un tipo con token de prueba y ausencia del valor en logs.
- [x] 3. Sin token en perfiles no Docker, impedir barrido, reclamo y POST tanto en scheduler como en entrega inmediata; probar estado, intentos y `ultimoError` intactos, entrega al configurar el token y que un `403` HTTP real siga siendo definitivo.
- [x] 4. Agregar `SERVICIO_TOKEN: ${SERVICIO_TOKEN}` en Compose y configurar tokens de prueba en los tests existentes de outbox/publicación, sin modificar `.env` ni incluir secretos reales.
- [x] 5. Añadir verificación opcional de `X-Service-Token` al stub de Preferencias cuando tenga `SERVICIO_TOKEN` configurada; probar token correcto, ausente e incorrecto y compatibilidad sin configuración, sin registrar valores sensibles.
- [x] 6. Documentar la variable, la diferencia entre perfiles, que sin token los avisos esperan sin consumir intentos ni perderse y el uso del stub con el mismo token en README. Aclarar que este cambio no modifica la autenticación entrante.
- [x] 7. Ejecutar las pruebas relevantes y `mvnw.cmd test` completo; validar Compose base y dev, `openspec validate token-servicio-outbox --strict --no-interactive` y `git diff --check`.
