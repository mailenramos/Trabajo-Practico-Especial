# Tareas

- [ ] 1. Confirmar con el grupo el contrato de MS6 y las URLs host/Docker de `USUARIOS_BASE_URL`; registrar los datos reales sin inventar puertos y resolver el comportamiento de respuestas no especificadas.
- [x] 2. Implementar modelo y repositorio de asignaciones con índice único `(eventoId, usuarioId)`, estados y fechas; probar unicidad, alta/reactivación concurrentes y revocación idempotente con Mongo/Testcontainers.
- [ ] 3. Implementar cliente MS6 con `RestClient`, query codificado, `X-Service-Token`, timeouts de 2 s/5 s y manejo de `404`, rol insuficiente y fallos transitorios; probar que los fallos no crean ni reactivan registros y que no se filtra el token.
- [ ] 4. Implementar POST/GET/DELETE de gestión con validación de correo, identidad, titularidad o `ADMIN`, respuestas e idempotencia según la spec; probar organizador ajeno, roles múltiples, evento inexistente, duplicado activo y reactivación.
- [x] 5. Implementar la consulta interna de autorización sin caché y sin headers de usuario; probar autorización activa, ausencia de asignación y efecto inmediato de revocación.
- [ ] 6. Incorporar `USUARIOS_BASE_URL` en configuración y Compose con los valores confirmados, y actualizar OpenAPI, README y Postman eliminando los avisos de propuesta pendiente.
- [ ] 7. Ejecutar pruebas relevantes y `mvnw.cmd test`, validar OpenSpec con `--strict` y `git diff --check`. No ejecutar tests contra MongoDB compartido; usar mocks o Testcontainers.
