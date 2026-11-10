# Design

## Context

El proyecto ya contiene Spring Boot 3.4.5, Java 17, Lombok y Springdoc 2.8.6 en `pom.xml`. Se usará `com.example.msvc.pagos.MsvcPagosApplication` con subpaquetes plurales. La base de desarrollo es `ticketya_payments` y la de pruebas `ticketya_payments_test`, creada manualmente en pgAdmin sin tablas.

## Goals / Non-Goals

**Goals:**

- Completar pagos y reintegros atómicos con validaciones monetarias e idempotencia robusta.
- Reutilizar las dependencias existentes y usar Lombok solo con `@Getter`, `@Setter` y `@NoArgsConstructor` en entidades.
- Probar comportamiento unitario, web y concurrencia contra PostgreSQL real.

**Non-Goals:**

- JWT, Dockerfile, docker-compose, jobs de recuperación, logs centralizados y trazabilidad distribuida.
- Persistir estados intermedios o implementar una pasarela real de dos fases.

## Decisions

### 1. Configuración existente y estructura

Se conserva Spring Boot 3.4.5, Java 17, Lombok y Springdoc 2.8.6. Las entidades no usarán `@Data`; solo `@Getter`, `@Setter` y `@NoArgsConstructor`. La clase principal será `com.example.msvc.pagos.MsvcPagosApplication`.

### 2. Properties y esquema

`application.properties` incluirá:

```properties
spring.application.name=msvc-pagos
server.port=8084
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ticketya_payments}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.jpa.defer-datasource-initialization=true
spring.sql.init.mode=always
ticketya.payment-simulation.payment-decision=APPROVED
ticketya.payment-simulation.refund-decision=SUCCESS
```

La contraseña no tendrá valor por defecto. El servicio escuchará en el puerto 8084; el perfil test no declarará `server.port`. Hibernate actualizará tablas con `@Table(name = "payments")` y `@Table(name = "refunds")`; `schema.sql` creará el índice parcial de compra aprobada y una prueba de arranque verificará que no falle.

El `@RestControllerAdvice` mapeará `HttpMessageNotReadableException`, `MissingRequestHeaderException`, `MethodArgumentNotValidException` y `MethodArgumentTypeMismatchException` a HTTP 400. `DataIntegrityViolationException` responderá 409 con `Conflicto de integridad de datos`; las demás excepciones no manejadas responderán 500 con `Error interno`, sin filtrar mensaje ni stack trace. Los handlers específicos conservarán prioridad. El cuerpo de error tendrá el formato `{timestamp, status, error, mensaje, campos?}`; `timestamp` será ISO-8601 y `campos` aparecerá únicamente en errores de validación 400.

### 3. Modelo, enums y validaciones

Los enums se persistirán con `@Enumerated(EnumType.STRING)`: `PaymentStatus` tendrá `EN_PROCESO`, `APROBADO` y `RECHAZADO`; `RefundStatus` tendrá `PENDIENTE`, `EXITOSO` y `FALLIDO`; `PaymentMethod` tendrá `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER` y `DIGITAL_WALLET`. Valores desconocidos producirán 400 mediante el advice y los endpoints. `DIGITAL_WALLET` siempre se persiste como `RECHAZADO`; los otros medios siguen `ticketya.payment-simulation.payment-decision` (`APPROVED`/`REJECTED`). `ticketya.payment-simulation.refund-decision` acepta únicamente `SUCCESS`/`FAILURE`. Ambas propiedades se validan al construir los servicios durante el arranque; valores inválidos producen un error claro y detienen la aplicación. Las entidades declararán unicidad JPA para `Payment.idempotencyKey`, `Refund.idempotencyKey` y `Refund.refundRequestId`. Los montos serán positivos y tendrán máximo dos decimales. La normalización con `setScale(2)` se aplicará antes de calcular la huella.

Las entidades administrarán timestamps mediante callbacks `@PrePersist` y `@PreUpdate`; no usarán `@CreationTimestamp` ni `@UpdateTimestamp`.

La verificación de persistencia de la tarea 2.1 deberá confirmar que Hibernate cree las tablas `payments` y `refunds`, sus columnas y las restricciones únicas en `ticketya_payments`. El índice parcial de `schema.sql` se verifica después, en la tarea 2.2.

### 4. Transacción única

Cada alta ejecutará validación, simulación, resolución y persistencia en una transacción única. `EN_PROCESO` y `PENDIENTE` permanecen en los enums, pero no se persisten como intermedios. Una pasarela real requeriría dos fases: persistir pendiente, llamar al proveedor y resolver mediante callback/reconciliación; esa arquitectura queda documentada para otra entrega.

### 5. Idempotencia y colisiones

La creación de pagos busca primero la clave, luego verifica que no haya una compra aprobada y finalmente procesa. Una clave existente se resuelve sin verificar compra aprobada ni ejecutar simulación. La creación de reintegros adquiere primero `PESSIMISTIC_WRITE` sobre `Payment`, busca clave, valida `refundRequestId`, estado aprobado, saldo y procesa. Una repetición con misma huella devuelve 200 antes de las validaciones posteriores, incluso si agotó el saldo.

La huella SHA-256 usa payload canónico y montos a escala 2. `Payment.idempotencyKey`, `Refund.idempotencyKey` y `Refund.refundRequestId` son únicos por anotaciones JPA separadas.

El servicio creador usa `saveAndFlush` dentro de la transacción para propagar la violación. El método externo que captura `DataIntegrityViolationException` no será `@Transactional` y no llamará internamente a un método transaccional de la misma clase. Usará `TransactionTemplate` o un bean separado para abrir una transacción nueva y buscar el registro ganador. Si no encuentra la clave, la colisión se atribuye al índice parcial de compra aprobada y se responde 409.

### 6. Reintegros y reintentos

Para el endpoint de reintento, que no recibe `Idempotency-Key`, primero se consultará únicamente el `paymentId` asociado al reintegro mediante una proyección; después se bloqueará ese `Payment` con `PESSIMISTIC_WRITE` y recién entonces se cargará el `Refund` completo (o se refrescará tras adquirir el lock). Así se evita cargar y reutilizar una copia desactualizada del reintegro en la caché de sesión. El saldo suma `EXITOSO + PENDIENTE` y excluye `FALLIDO`; un acumulado exactamente igual al importe cobrado se acepta. El reintento de un `FALLIDO` valida la suma de su monto y `EXITOSO + PENDIENTE` de los otros reintegros; si supera el importe cobrado responde 409 y conserva estado/contador. Si el simulador falla, responde 200, permanece `FALLIDO` e incrementa `attemptCount`; si tiene éxito, responde 200 y pasa a `EXITOSO`. Dos reintentos simultáneos quedan serializados y solo uno puede prosperar.

### 7. Simulación, perfil test y datos iniciales

El simulador de reintegros usará `ticketya.payment-simulation.refund-decision=SUCCESS`. Las pruebas usarán `@TestPropertySource` o mock para cubrir fallo y reintento. `DataInitializer` cargará datos fijos solo si las tablas están vacías, calculará `requestFingerprint` usando la misma función de huella que las solicitudes y no duplicará datos al reiniciar; tendrá `@Profile("!test")`. `src/test/resources/application-test.properties` apuntará a `ticketya_payments_test`; las pruebas usarán `@ActiveProfiles("test")` y limpiarán tablas entre casos.

## Risks / Trade-offs

- [Riesgo] Una transacción única no modela una pasarela externa lenta → [Mitigación] documentar la futura arquitectura de dos fases sin implementarla.
- [Riesgo] `ddl-auto=update` no versiona cambios → [Mitigación] verificar esquema e índice en cada entorno.
- [Riesgo] El bloqueo pesimista reduce paralelismo → [Mitigación] mantener breve la transacción.
- [Riesgo] Capturar una colisión dentro de la misma transacción deja el contexto inválido → [Mitigación] método externo no transaccional y `TransactionTemplate`/bean separado.

## Migration Plan

1. Confirmar dependencias existentes y marcar la configuración base ya realizada.
2. Crear properties, entidades, enums, constraints y `schema.sql`.
3. Implementar servicios transaccionales, idempotencia, endpoints y DataInitializer.
4. Crear perfil y base de pruebas, README y Postman.
5. Ejecutar pruebas unitarias, web y concurrentes.

## Open Questions

- Confirmar nombres finales de variables adicionales y puerto con el resto de TicketYa.
- Definir en una entrega futura la reconciliación de una pasarela real de dos fases.
