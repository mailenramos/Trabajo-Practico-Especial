# Tareas

- [x] 1. Agregar `requiereFila` al modelo con default `false`; probar eventos nuevos y lectura de documentos Mongo antiguos sin campo, sin migrarlos.
- [x] 2. Ampliar el DTO y GET interno con los campos del diseño, manteniendo `fechaInicio`, formatos y precios `null`; probar datos, estado actual, ausencia de caché, `404` y compatibilidad con Compras.
- [x] 3. Implementar la actualización con booleano obligatorio y validación estricta: evaluar `404` por inexistencia, luego `400` por body inválido y finalmente `409` solo al activar un cancelado; permitir desactivarlo, sin requerir headers de usuario.
- [x] 4. Probar activación, desactivación, rechazo de activación en cancelado y desactivación permitida, el orden `404`/`400`/`409`, idempotencia sin escritura ni incremento de versión —incluido `false` legado también en cancelado— y control optimista ante concurrencia, con Mongo/Testcontainers.
- [x] 5. Al implementar, actualizar la documentación OpenAPI y verificar el README y las requests Postman de política de fila, quitando sus avisos de funcionalidad pendiente; conservar rutas internas fuera del Gateway y sin agregar autenticación entrante.
- [x] 6. Ejecutar pruebas relevantes y `mvnw.cmd test`; validar `openspec validate politica-fila-requiere-fila --strict --no-interactive` y `git diff --check`.
