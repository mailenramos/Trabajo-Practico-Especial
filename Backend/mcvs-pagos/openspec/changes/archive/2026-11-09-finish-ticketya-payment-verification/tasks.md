# Tasks

## 1. Verificación de repositorios PostgreSQL

- [ ] 1.1 Agregar cobertura de integración PostgreSQL para las consultas de repositorio por claves y `refundRequestId`, pagos por compra, acumulados por estado, proyección `paymentId` y bloqueo `PESSIMISTIC_WRITE`; verificar resultados y exclusión mutua al ejecutar las pruebas con `RUN_POSTGRESQL_TESTS=true` y la base de test disponible.
- [ ] 1.2 Ejecutar los cuatro escenarios de concurrencia PostgreSQL existentes y verificar sus invariantes de persistencia y saldo; si faltan variables o base, registrar la verificación como bloqueada e indicar los requisitos exactos, sin contarla como pasada.

## 2. Gate de calidad final

- [ ] 2.1 Ejecutar `.\mvnw.cmd test`, `.\mvnw.cmd package` y `openspec.cmd validate implement-ticketya-payment-service --strict`; verificar el código de salida de cada comando y reportar pruebas pasadas, fallidas y omitidas, distinguiendo pruebas PostgreSQL ejecutadas de las omitidas por configuración.
