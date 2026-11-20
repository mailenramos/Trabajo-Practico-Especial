# Especificación de referencia del microservicio

## Purpose
Proveer una referencia verificada de la arquitectura actual de `msvc-entradas`, la configuración de persistencia, las respuestas de error, las discrepancias conocidas de contratos y el trabajo pendiente.

## Status
- **Implementado en código/configuración:** servicio Spring Boot, REST controller, capas de servicio/repositorios/entidades, persistencia PostgreSQL, migraciones Flyway y mapeo centralizado de excepciones.
- **Verificado con tests automatizados:** servicio (`EntradaServiceImplTest`, `IngresoServiceTest`), HTTP (`EntradaControllerTest`), cliente de MS1 (`AutorizacionAccesoClienteRestTest`) y, con Docker, PostgreSQL real (`EntradaConcurrencyIntegrationTest`, `IngresoConcurrencyIntegrationTest`).
- **Requiere Docker para verificarse:** migraciones Flyway, validación de esquema de Hibernate contra PostgreSQL y concurrencia real; sin Docker esas clases se saltean.

## Requirements

### Requirement: Mantener el esquema de persistencia bajo Flyway
El servicio MUST usar migraciones Flyway para los cambios de esquema y configurar Hibernate para validar, en lugar de generar, el esquema de la base de datos.

#### Scenario: Aplicación configurada para persistencia
- **WHEN** el servicio inicia con su fuente de datos configurada
- **THEN** Flyway está habilitado en `classpath:db/migration` y Hibernate usa `ddl-auto: validate`

### Requirement: Devolver el cuerpo de error centralizado actual
El manejador de excepciones MUST mapear los errores gestionados de validación, acceso, recurso inexistente y conflicto de negocio al formato actual: `timestamp`, `status`, `error`, `mensaje` y `campos` opcional, sin una propiedad independiente `codigo`.

#### Scenario: Error de aplicación gestionado
- **WHEN** uno de los manejadores registrados procesa un error de aplicación o de validación de solicitud
- **THEN** la respuesta usa los campos actuales de `ErrorResponseDTO` y serializa `timestamp` como un instante UTC con sufijo `Z`

## Architecture and Technical Decisions
- El módulo usa controllers Spring MVC, una abstracción `EntradaService` con implementación `EntradaServiceImpl`, repositorios Spring Data JPA, entidades JPA, DTOs tipo record y un manejador de errores `@RestControllerAdvice`.
- La configuración local nombra a la aplicación `msvc-entradas`, selecciona el puerto 8085, se conecta a la base PostgreSQL `entradas` en `localhost:5433`, habilita Flyway y configura a Hibernate para validar el esquema.
- Flyway V1 crea las tablas de emisiones, copias de eventos/sectores y entradas. Agrega un ID de compra único, UUID de QR único, restricción de estados, índices para consultas por cliente/compra/evento e índice único parcial para asientos numerados vigentes. V2 convierte `fila` a entero y exige que fila y número sean ambos nulos o ambos no nulos; `fila` se representa como `Integer` nullable en Java y como `integer` nullable en OpenAPI. V3 convierte `copia_evento.comienza_en` a `TIMESTAMP WITH TIME ZONE`. V4 agrega el estado `UTILIZADA`, reemplaza el índice de asientos por `uk_entrada_lugar_ocupado` (VIGENTE o UTILIZADA) y crea `control_eventos` e `ingresos`.
- Los IDs de eventos y sectores son texto en este servicio; los identificadores de compra y entrada son UUIDs.
- La aplicación establece UTC como zona horaria predeterminada de la JVM. La zona horaria JDBC de JPA y las marcas de tiempo generadas por el servicio/entidades también usan UTC.
- Los campos del contrato que representan instantes (`comienzaEn` y el `timestamp` de error) usan `Instant` en Java y se serializan en UTC con sufijo `Z`.

## Partially Implemented or Unverified
- El README documenta pruebas manuales con Postman/PostgreSQL. Los tests automatizados de este repositorio no verifican esas afirmaciones de manera independiente.

## Contract Alignment
- Para los campos revisados, `openapi-entradas.yaml` refleja la implementación: declara `fila` como `integer` nullable, `comienzaEn` como `date-time` con ejemplo UTC terminado en `Z`, y el error con `timestamp`, `status`, `error`, `mensaje` y `campos` opcional, sin una propiedad independiente `codigo`.

## Pending
- Consulta de entradas para `ORGANIZADOR` con validación de titularidad de eventos, si el producto la requiere.
- Ejecutar los tests de integración con Docker en cada entrega para verificar migraciones y concurrencia.

## Verification Evidence
- Al 2026-11-20, `mvnw test` con Docker: 61 tests, 0 fallos, 0 salteados (incluye los 10 de PostgreSQL con Testcontainers).
- Sin Docker, las 2 clases de Testcontainers (10 tests) se saltean.
