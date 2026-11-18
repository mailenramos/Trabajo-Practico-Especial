# Tasks

- [x] 1. Configurar `MongoTransactionManager` sobre el `MongoDatabaseFactory` de MS1 y verificar la configuración existente `replicaSet=rs0`; mantener las pruebas de integración con MongoDB replica set y no modificar Compose.
- [x] 2. Implementar el documento y repositorio de outbox, con estados, fechas, lease, campos de reintento e índices para ID determinístico único y selección de avisos elegibles.
- [ ] 3. Implementar el dispatcher HTTP: timeouts 2 s/5 s, header `X-Aviso-Id`, clasificación de errores, máximo 8 intentos contados al reclamar, backoff 30 s × 2 hasta 15 min, sin jitter y transición a `ERROR_FINAL`.
- [ ] 4. Implementar el Scheduler común configurable para procesar avisos y recuperar leases vencidos; conservar el intervalo actual de 60 s por defecto.
- [ ] 5. Migrar la cancelación para guardar evento y aviso `cancelacion-{eventoId}` en una transacción; mantener el intento inmediato posterior al commit y la respuesta actual aunque Compras falle.
- [ ] 6. Hacer backfill idempotente de avisos legacy pendientes usando el ID determinístico y verificar que no queden pendientes sin migrar.
- [ ] 7. Retirar el flag de cancelación y el scheduler dedicado solo después de verificar el backfill y confirmar que el flujo nuevo procesa los avisos.
- [ ] 8. Agregar y ejecutar pruebas de integración para rollback, destino caído, clasificación 4xx/5xx/timeouts, header de idempotencia, reentrega duplicada, dos instancias, lease vencido, agotamiento y compatibilidad del flujo de cancelación.
- [ ] 9. Actualizar README, diseño, configuración y spec existente `eventos`; ejecutar `openspec validate generalizar-outbox-ms1 --strict --no-interactive`.
