# Proposal

## Why

TicketYa necesita un microservicio independiente para registrar pagos y reintegros de compras. La base tecnológica ya está encaminada en el Maven del proyecto, por lo que esta etapa debe consolidar el contrato funcional, la consistencia transaccional, la idempotencia y las pruebas necesarias antes de implementar el resto del servicio.

## What Changes

- Completar `msvc-pagos` como aplicación Spring Boot 3.4.5 con Java 17, Lombok y Springdoc 2.8.6 ya configurados en `pom.xml`.
- Usar el paquete `com.example.msvc.pagos`, la clase `MsvcPagosApplication` y subpaquetes `controllers`, `entities`, `repositories`, `services`, `dto`, `enums` y `exceptions`.
- Configurar `application.properties`, PostgreSQL, Hibernate `ddl-auto=update`, `schema.sql` y las propiedades de simulación.
- Crear y resolver cada pago o reintegro en una única transacción; `EN_PROCESO` y `PENDIENTE` permanecen en los enums, pero no se persisten como estados intermedios.
- Aplicar idempotencia, unicidad JPA separada, colisiones de persistencia y bloqueo pesimista según las especificaciones.
- Validar medios de pago, montos positivos con hasta dos decimales y huellas normalizadas.
- Incorporar pruebas unitarias, web y de concurrencia contra PostgreSQL real, además de configuración aislada para el perfil `test`.
- Documentar README y colección de Postman con los datos del `DataInitializer`.
- Mantener fuera de alcance JWT, Docker, recuperación automática, logs centralizados y trazabilidad distribuida.

## Capabilities

### New Capabilities

- `payment-processing`: recepción externa, validación, simulación atómica, persistencia y consulta de pagos por compra.
- `payment-refunds`: creación, validación, persistencia, consulta y reintento de reintegros del microservicio 3.
- `operation-idempotency`: huellas normalizadas, deduplicación, colisiones, unicidad y concurrencia.

### Modified Capabilities

Ninguna. El proyecto no contiene especificaciones existentes.

## Impact

- `pom.xml`: se toma como base Spring Boot 3.4.5, Java 17, Lombok y Springdoc 2.8.6 ya configurados.
- `src/main/java/com/example/msvc/pagos`: entidades, DTOs, enums, repositorios, servicios, controllers, excepciones y aplicación principal.
- `src/main/resources/application.properties` y `schema.sql`: configuración, conexión, simulación e índice parcial.
- `src/test/resources/application-test.properties`: base `ticketya_payments_test`, perfil de pruebas y limpieza aislada.
- README y colección Postman.
- Pruebas unitarias, web y concurrentes contra PostgreSQL.

