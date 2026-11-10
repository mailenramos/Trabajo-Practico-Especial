# Proposal

## Why

La exclusión de un asiento numerado se apoya en un índice único parcial de PostgreSQL, pero los tests existentes simulan los repositorios y no comprueban esa garantía ante solicitudes concurrentes. Este cambio agrega evidencia de integración contra PostgreSQL real, ejecutando las migraciones Flyway del servicio.

## What Changes

- Agregar una prueba de integración con Spring Boot Test y Testcontainers para emitir concurrentemente solicitudes con distintos `idCompra` para el mismo evento, sector, fila y número.
- Comprobar que una sola solicitud se emita correctamente, que las restantes terminen con el conflicto `ENTRADA_VIGENTE_DUPLICADA` y que PostgreSQL conserve exactamente una entrada `VIGENTE` para ese asiento.
- Mantener sin cambios el código de producción, las migraciones, las dependencias y los 15 tests unitarios actuales.

## Capabilities

### New Capabilities

### Modified Capabilities
- `ticket-issuance`: agregar el escenario concurrente de emisión para el mismo asiento numerado y sus resultados observables.

## Impact

- Nuevo test de integración bajo `src/test/java`, con contexto Spring y PostgreSQL administrado por Testcontainers.
- Se usarán las dependencias de test ya declaradas en `pom.xml`: Spring Boot Test, Spring Boot Testcontainers, Testcontainers JUnit Jupiter y PostgreSQL; no se requieren cambios de dependencias.
- Se ejecutarán las migraciones Flyway existentes al inicializar el contexto. No se modificarán endpoints ni se invocarán endpoints HTTP: la comprobación será de la emisión en el servicio y del estado persistido consultado en PostgreSQL.
- La prueba requiere Docker disponible para iniciar Testcontainers.
- `ticket-lifecycle` aporta el estado inicial esperado `VIGENTE`; `service-baseline` registra que la suite actual no verifica contra PostgreSQL real. No se modifican esas capacidades porque el cambio agrega cobertura de concurrencia a la emisión, sin alterar el comportamiento de estados ni la arquitectura del servicio.
