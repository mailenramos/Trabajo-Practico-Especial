# Tareas

**Contrato confirmado:** el equipo de Reservas confirmó que repetir `CANCELADA` devuelve `204` y que es terminal; `ACTIVA` o `SUSPENDIDA` sobre una venta cancelada responden `409`. Documentarlo como confirmado en README.

- [x] 1. Admitir método HTTP y body opcionales en la outbox, preservando `POST` y payload legacy; preparar el aviso `PUT` sin body a MS7 y `FILAS_BASE_URL` en host, Compose y `.env.example`.
- [x] 2. Persistir atómicamente la intención interna y el trabajo de cancelación de Reservas, con ID, motivo y correlación estables; aplicar las guardas de suspensión, reactivación y finalización sin estados públicos nuevos. Si Reservas responde `409` al suspender o reactivar, responder `409` sin modificar el evento, no `503`.
- [x] 3. Extender el dispatcher para recuperar el trabajo de Reservas; condicionar la confirmación transaccional al reclamo vigente con los campos existentes, crear los avisos a Compras y MS7 e invalidar caché después del commit.
- [x] 4. Probar flujo feliz, corte antes de llamar a Reservas, respuesta perdida, `503` con intención pendiente, agotamiento a `ERROR_FINAL`, token ausente, avisos legacy sin método, aviso a MS7 con y sin fila y no duplicación; usar MongoDB de Testcontainers, nunca local.
- [ ] 5. Actualizar README, OpenAPI y Postman con los códigos, el `503` recuperable, `ERROR_FINAL`, el contrato confirmado con Reservas, la configuración y los avisos; ejecutar `mvnw.cmd test`, `openspec validate cancelacion-recuperable --strict` y `git diff --check`.
