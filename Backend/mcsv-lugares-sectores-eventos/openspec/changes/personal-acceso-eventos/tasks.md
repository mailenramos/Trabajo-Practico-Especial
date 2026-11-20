# Tareas

- [x] 1. Registrar el contrato confirmado de MS6: UUID textual, roles y activo; default host `http://localhost:8087` y URL Docker explícita, sin nombre de servicio inventado.
- [x] 2. Implementar modelo y repositorio de asignaciones con índice único `(eventoId, usuarioId)`, estados y fechas; probar unicidad, alta/reactivación concurrentes y revocación idempotente con Mongo/Testcontainers.
- [x] 3. Implementar cliente MS6 con `RestClient`, query codificado, `X-Service-Token`, correlación, timeouts de 2 s/5 s y manejo de respuestas, usuario inactivo y fallos; probar que no crean ni reactivan registros y que no se filtra el token.
- [x] 4. Implementar POST/GET/DELETE de gestión con validación de correo, identidad, titularidad o `ADMIN`, respuestas e idempotencia según la spec; probar organizador ajeno, roles múltiples, evento inexistente, duplicado activo y reactivación.
- [x] 5. Implementar la consulta interna de autorización sin caché y sin headers de usuario; probar autorización activa, ausencia de asignación y efecto inmediato de revocación.
- [x] 6. Incorporar `USUARIOS_BASE_URL` en configuración y Compose con los valores confirmados, y actualizar OpenAPI, README y Postman eliminando los avisos de propuesta pendiente.
- [x] 7. Ejecutar pruebas relevantes y `mvnw.cmd test`, validar OpenSpec con `--strict` y `git diff --check`. No ejecutar tests contra MongoDB compartido; usar mocks o Testcontainers.
