# Tareas

- [x] 1. Implementar filtro HTTP que valida IDs con máximo 100 caracteres y solo letras ASCII, números, guion, guion bajo y punto, o genera UUID; MDC, header y log por solicitud salvo `/actuator/**`; probar ID inválido, exclusión, errores y limpieza.
- [x] 2. Configurar el patrón Logback con `correlationId` y salida stdout; revisar los logs tocados para no exponer tokens, cuerpos, contraseñas ni parámetros de consulta y cubrirlo con pruebas.
- [x] 3. Propagar `X-Correlation-Id` en todas las operaciones del cliente Reservas, incluidas operaciones programadas sin contexto HTTP; probar headers, fallos y limpieza de contexto sin alterar el contrato existente.
- [x] 4. Agregar `correlationId` opcional a los avisos y capturarlo al crear publicación y cancelación en sus transacciones; mantener compatibilidad de documentos antiguos y backfill, con pruebas MongoDB de Testcontainers.
- [x] 5. Propagar el ID guardado en el dispatcher y cliente outbox para entregas inmediatas y programadas, generar UUID para avisos sin ID y restaurar el MDC; probar reintentos, errores, headers y aislamiento entre avisos.
- [x] 6. Documentar en README los logs, propagación, fallback y stdout; dejar indicado que el cliente MS6 aún no existe y debe integrar el mecanismo al implementarse.
- [x] 7. Ejecutar pruebas relevantes y `mvnw.cmd test` con Testcontainers, nunca MongoDB local; correr `openspec validate trazabilidad-correlacion --strict --no-interactive` y `git diff --check`.
