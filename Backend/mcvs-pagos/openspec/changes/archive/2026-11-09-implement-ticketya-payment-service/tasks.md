# Tasks

## 1. Configuración base del proyecto

- [x] 1.1 Verificar que `pom.xml` ya contiene Spring Boot 3.4.5, Java 17, Lombok y Springdoc 2.8.6; verificar con compilación Maven sin modificar dependencias existentes.
- [x] 1.2 Verificar la aplicación base `com.example.msvc.pagos.MsvcPagosApplication` y los subpaquetes `controllers`, `entities`, `repositories`, `services`, `dto`, `enums` y `exceptions`; verificar el component scan.
- [x] 1.3 Completar `application.properties` con `spring.application.name=msvc-pagos`, `server.port=8084`, la URL `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ticketya_payments}`, username `${DB_USER:postgres}`, password `${DB_PASSWORD}`, Hibernate `ddl-auto=update`, inicialización SQL y propiedades de simulación; validar al arrancar `payment-decision` (`APPROVED`/`REJECTED`) y `refund-decision` (`SUCCESS`/`FAILURE`), sin valor predeterminado para la contraseña.

## 2. Persistencia, enums y datos iniciales

- [x] 2.1 Crear `Payment` y `Refund` con `@Table(name = "payments")`/`@Table(name = "refunds")`, UUID, BigDecimal, timestamps y `@Enumerated(EnumType.STRING)`; crear `PaymentStatus`, `RefundStatus` y `PaymentMethod` con todos sus valores; declarar unicidad JPA para `Payment.idempotencyKey`, `Refund.idempotencyKey` y `Refund.refundRequestId`; usar únicamente `@Getter`, `@Setter` y `@NoArgsConstructor` de Lombok; verificar compilación y que Hibernate cree tablas y restricciones en `ticketya_payments`.
- [x] 2.2 Crear `schema.sql` con `uk_payment_approved_purchase` después de que exista la tabla `payments`; verificar en ese punto que el índice se cree sin errores.
- [x] 2.3 Validar que los montos sean positivos y tengan como máximo dos decimales; verificar casos válidos e inválidos con pruebas.
- [x] 2.4 Crear repositorios con consultas por clave, `refundRequestId`, compra, acumulados y `PESSIMISTIC_WRITE` sobre `Payment`; verificar consultas y bloqueo.
- [x] 2.5 Administrar timestamps de las entidades solo con `@PrePersist` y `@PreUpdate`, quitando `@CreationTimestamp` y `@UpdateTimestamp`; verificar que alta y actualización asignen los tiempos esperados.

## 3. Infraestructura común de idempotencia

- [x] 3.1 Implementar payload canónico, normalización monetaria y huella SHA-256; verificar únicamente que payloads monetariamente equivalentes produzcan la misma huella.
- [x] 3.2 Implementar `DataInitializer` con UUIDs y claves fijas, solo cuando las tablas estén vacías y con `@Profile("!test")`; calcular `requestFingerprint` con la misma función de huella de 3.1 y verificar que no se dupliquen datos al reiniciar.
- [x] 3.3 Implementar `saveAndFlush` dentro de la transacción creadora y captura de `DataIntegrityViolationException` en un método externo no transaccional; resolver la búsqueda en una transacción nueva mediante `TransactionTemplate` o bean separado, nunca por llamada interna a `@Transactional`; verificar que una clave encontrada devuelva 200/409 y que una colisión sin registro devuelva 409 por índice parcial.
- [x] 3.4 Implementar excepciones de dominio y `@RestControllerAdvice`; mapear errores de solicitud a 400, `DataIntegrityViolationException` a 409 con mensaje genérico y excepciones no contempladas a 500 sin filtrar detalles; mantener prioridad de handlers específicos y responder con `{timestamp, status, error, mensaje, campos?}`, timestamp ISO-8601 y `campos` solo en errores de validación 400; verificar con pruebas.

## 4. Pagos

- [x] 4.1 Crear DTOs y validaciones para datos enviados por el microservicio 3; verificar compilación y reglas de validación del servicio para faltantes, medio desconocido, monto inválido y escala excesiva; reservar los códigos HTTP para 3.4 y 4.4.
- [x] 4.2 Implementar simulador y servicio transaccional único: `DIGITAL_WALLET` siempre se persiste `RECHAZADO`, los demás medios siguen `payment-decision` (`APPROVED`/`REJECTED`), y una alta nueva rechazada responde 201; validar la propiedad al arrancar y probar estados y medios con pruebas unitarias.
- [x] 4.3 Aplicar el orden: buscar Idempotency-Key, verificar compra aprobada si es nueva, procesar y ejecutar `flush` dentro del try; verificar repetición, conflicto, nuevo intento tras rechazo y carrera del índice con pruebas unitarias del servicio.
- [x] 4.4 Implementar endpoints y OpenAPI; verificar con `@WebMvcTest` y MockMvc creación nueva 201, repetición 200, consultas por UUID y `purchaseId` 200 incluso con lista vacía, header ausente 400 y errores 400/404/409.

## 5. Reintegros

- [x] 5.1 Crear DTOs y servicios para `refundRequestId`, `amount`, `reason` e `Idempotency-Key`; aplicar el orden lock Payment, clave, refundRequestId, pago aprobado, saldo y procesamiento; verificar las reglas en pruebas unitarias del servicio y reservar los códigos HTTP para 5.7.
- [x] 5.2 Implementar creación con `PESSIMISTIC_WRITE`; verificar `EXITOSO + PENDIENTE + nuevo`, aceptación cuando el acumulado alcanza exactamente el cobro, exclusión de `FALLIDO`, resultado directo `EXITOSO`/`FALLIDO` y repetición resuelta 200 aunque agotó saldo.
- [x] 5.3 Cubrir fallo y reintento mediante `@TestPropertySource` o mock del simulador; aceptar y validar al arrancar únicamente `refund-decision=SUCCESS` o `FAILURE`; verificar creación 201 y que el reintento responda 200, incremente `attemptCount` y permanezca `FALLIDO` ante fallo o pase a `EXITOSO` ante éxito.
- [x] 5.4 Implementar el reintento sin `Idempotency-Key`: consultar primero solo `paymentId` mediante proyección, bloquear `Payment` con `PESSIMISTIC_WRITE` y después cargar el `Refund` completo o refrescarlo tras el lock; verificar mismo UUID/refundRequestId, `attemptCount` incrementado y 409 para cualquier estado distinto de `FALLIDO`.
- [x] 5.5 Validar saldo durante reintentos con el monto fallido más `EXITOSO + PENDIENTE` de otros reintegros; si supera el importe cobrado, verificar 409, estado `FALLIDO` y contador sin incrementar.
- [x] 5.6 Agregar prueba unitaria secuencial de un segundo reintento sobre un reintegro ya `EXITOSO`; verificar que responda 409 y no modifique el registro.
- [x] 5.7 Completar endpoints y OpenAPI; verificar con `@WebMvcTest` y MockMvc creación 201, repetición 200, consulta 200, consulta/reintento inexistente 404, `Idempotency-Key` ausente en creación 400 y errores 400/404/409.

## 6. Perfil PostgreSQL de pruebas, concurrencia y documentación

- [x] 6.1 Crear manualmente en pgAdmin la base vacía `ticketya_payments_test` sin tablas y agregar `src/test/resources/application-test.properties` con conexión, `spring.jpa.hibernate.ddl-auto=create-drop` o equivalente de test y limpieza controlada; usar `@ActiveProfiles("test")`, requerir la variable de entorno `DB_PASSWORD` y verificar aislamiento.
- [x] 6.2 Crear prueba PostgreSQL real con `payment-decision=APPROVED` y dos pagos simultáneos con la misma clave; verificar una operación persistida y respuestas idempotentes.
- [x] 6.3 Ampliar la prueba PostgreSQL con dos pagos simultáneos de la misma compra y claves distintas; verificar como máximo un `APROBADO` y un 409.
- [x] 6.4 Agregar prueba PostgreSQL de dos reintegros simultáneos sobre el mismo pago y dos reintentos simultáneos del mismo reintegro; verificar lock pesimista y una sola operación válida.
- [x] 6.5 Crear/actualizar README con configuración de bases, variables, ejecución, códigos, estados y futura pasarela de dos fases; documentar que un 409 en `/reintentar` indica que el reintegro ya no está `FALLIDO` y que el cliente debe consultar su estado, y que un pago rechazado se reintenta con una `Idempotency-Key` nueva; verificar comandos documentados.
- [x] 6.6 Crear colección Postman con pagos, consultas, reintegros, reintentos, headers y UUIDs/claves del DataInitializer; verificar requests ejecutables.
- [x] 6.7 Ejecutar `mvn test`, `mvn package` y `openspec.cmd validate implement-ticketya-payment-service --strict`; verificar Java 17, pruebas y validación OpenSpec. Las pruebas PostgreSQL quedan condicionadas a configurar la base de test.
