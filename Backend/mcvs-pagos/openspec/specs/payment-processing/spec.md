# payment-processing Specification

## Purpose
Esta capacidad permite recibir del microservicio 3, validar, resolver y consultar pagos asociados a compras externas de forma determinística y atómica.

## Requirements

### Requirement: Crear pagos con datos enviados por el microservicio 3
El sistema SHALL aceptar `purchaseId`, monto, medio de pago e `Idempotency-Key` enviados por el microservicio 3; no SHALL generar esos datos externos.

#### Scenario: Solicitud válida
- **WHEN** se recibe un POST con datos válidos y una clave nueva
- **THEN** el sistema crea, simula y resuelve el pago en una transacción, responde HTTP 201 y persiste `APROBADO` o `RECHAZADO`

#### Scenario: Datos inválidos
- **WHEN** falta un dato obligatorio, el monto no es positivo, tiene más de dos decimales o el medio no pertenece al enum
- **THEN** el sistema responde HTTP 400 y no crea el pago

#### Scenario: Idempotency-Key ausente
- **WHEN** se crea un pago sin el header `Idempotency-Key`
- **THEN** el sistema responde HTTP 400 y no crea el pago

### Requirement: Resolver pagos mediante simulación determinística
El sistema SHALL usar `ticketya.payment-simulation.payment-decision`, cuyo valor SHALL ser `APPROVED` o `REJECTED`, sin aleatoriedad ni datos sensibles; un valor diferente SHALL impedir el inicio de la aplicación. `EN_PROCESO` existe en el enum, pero no se persiste como estado intermedio.

#### Scenario: Aprobación
- **WHEN** la propiedad indica `APPROVED`
- **THEN** el pago se persiste como `APROBADO`

#### Scenario: Rechazo
- **WHEN** la propiedad indica `REJECTED`
- **THEN** el pago se persiste como `RECHAZADO`

### Requirement: Aceptar solo medios de pago definidos
El sistema SHALL aceptar únicamente `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER` o `DIGITAL_WALLET`.

#### Scenario: Medio desconocido
- **WHEN** el payload contiene otro valor
- **THEN** el sistema responde HTTP 400

### Requirement: Resolver pagos según el medio de pago
El sistema SHALL persistir siempre como `RECHAZADO` un pago `DIGITAL_WALLET`. Los medios `CREDIT_CARD`, `DEBIT_CARD` y `BANK_TRANSFER` SHALL resolverse según `payment-decision`.

#### Scenario: DIGITAL_WALLET aunque la propiedad apruebe
- **WHEN** se crea un pago nuevo `DIGITAL_WALLET` y `payment-decision=APPROVED`
- **THEN** se persiste `RECHAZADO` y la creación responde HTTP 201

#### Scenario: Otros medios aprobados
- **WHEN** se crea un pago nuevo con `CREDIT_CARD`, `DEBIT_CARD` o `BANK_TRANSFER` y `payment-decision=APPROVED`
- **THEN** se persiste `APROBADO`

#### Scenario: Otros medios rechazados
- **WHEN** se crea un pago nuevo con `CREDIT_CARD`, `DEBIT_CARD` o `BANK_TRANSFER` y `payment-decision=REJECTED`
- **THEN** se persiste `RECHAZADO`

### Requirement: Impedir múltiples pagos aprobados por compra
El sistema SHALL permitir como máximo un pago `APROBADO` por `purchaseId`, incluso ante concurrencia, y SHALL permitir nuevos intentos si los anteriores fueron `RECHAZADO`.

#### Scenario: Compra con pago rechazado
- **WHEN** una compra con pagos solo rechazados recibe un nuevo intento
- **THEN** el sistema permite procesarlo y responde HTTP 201 si es nuevo

#### Scenario: Compra ya aprobada
- **WHEN** una clave nueva se usa para una compra con pago `APROBADO`
- **THEN** el sistema responde HTTP 409 y no procesa otro cobro

#### Scenario: Dos aprobaciones simultáneas
- **WHEN** dos claves distintas intentan aprobar pagos para la misma compra
- **THEN** solo una queda `APROBADO` y la otra responde HTTP 409

### Requirement: Consultar pagos
El sistema SHALL permitir consultar por UUID y listar por `purchaseId`.

#### Scenario: Consulta existente
- **WHEN** se consulta un pago registrado
- **THEN** responde HTTP 200 con sus datos y estado resuelto

#### Scenario: Consulta inexistente
- **WHEN** se consulta un UUID no registrado
- **THEN** responde HTTP 404

#### Scenario: Listado por purchaseId
- **WHEN** se listan pagos asociados a un `purchaseId`
- **THEN** responde HTTP 200 con la lista de pagos, incluso si está vacía

### Requirement: Exponer el contrato REST de pagos
El sistema SHALL exponer los endpoints definidos para crear y consultar pagos.

#### Scenario: Códigos del contrato
- **WHEN** se invoca el endpoint de creación o consulta
- **THEN** una creación nueva responde 201, una repetición idempotente 200 y una consulta 200

### Requirement: Configurar puerto HTTP
El servicio SHALL escuchar en el puerto `8084` mediante `server.port`; el perfil `test` SHALL conservar su configuración sin establecer ese puerto.

#### Scenario: Puerto de servicio
- **WHEN** la aplicación inicia con la configuración predeterminada fuera del perfil `test`
- **THEN** el servidor HTTP escucha en el puerto `8084`

### Requirement: Normalizar errores HTTP
El advice SHALL responder con `{timestamp, status, error, mensaje, campos?}`, con timestamp ISO-8601 y `campos` solo en errores de validación 400. `DataIntegrityViolationException` SHALL mapearse a HTTP 409 con mensaje `Conflicto de integridad de datos`. Las excepciones específicas SHALL conservar prioridad sobre el handler genérico.

#### Scenario: Error interno no esperado
- **WHEN** se produce una excepción no contemplada por un handler más específico
- **THEN** responde HTTP 500 con `mensaje` igual a `Error interno`, sin exponer el mensaje ni el stack trace de la excepción

### Requirement: Validar decisiones del simulador al arrancar
Al iniciar, el sistema SHALL validar `ticketya.payment-simulation.payment-decision` como `APPROVED` o `REJECTED` y `ticketya.payment-simulation.refund-decision` como `SUCCESS` o `FAILURE`; cualquier otro valor SHALL impedir el inicio con un mensaje claro.

#### Scenario: Propiedades de simulación inválidas
- **WHEN** cualquiera de las propiedades de decisión tiene un valor fuera de su conjunto permitido
- **THEN** la aplicación falla al iniciar e indica claramente qué propiedad debe corregirse
