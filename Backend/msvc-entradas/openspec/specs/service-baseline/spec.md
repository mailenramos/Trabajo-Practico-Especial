# Especificación de referencia del microservicio

## Purpose
Proveer una referencia verificada de la arquitectura actual de `msvc-entradas`, la configuración de persistencia, las respuestas de error, las discrepancias conocidas de contratos y el trabajo pendiente.

## Status
- **Implementado en código/configuración:** servicio Spring Boot, REST controller, capas de servicio/repositorios/entidades, persistencia PostgreSQL, migraciones Flyway y mapeo centralizado de excepciones.
- **Verificado parcialmente:** la clase de tests unitarios específica pasa, pero la evidencia se limita al comportamiento del servicio con dependencias simuladas.
- **No comprobado mediante los tests automatizados actuales:** inicio de la aplicación contra PostgreSQL, ejecución de migraciones, validación de esquema de Hibernate contra una base activa, comportamiento del controller y comunicación entre servicios.

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
- Flyway V1 crea las tablas de emisiones, copias de eventos/sectores y entradas. Agrega un ID de compra único, UUID de QR único, restricción de estados, índices para consultas por cliente/compra/evento e índice único parcial para asientos numerados vigentes. V2 convierte `fila` a entero y exige que fila y número sean ambos nulos o ambos no nulos; `fila` se representa como `Integer` nullable en Java y como `integer` nullable en OpenAPI. V3 convierte `copia_evento.comienza_en` a `TIMESTAMP WITH TIME ZONE`.
- Los IDs de eventos y sectores son texto en este servicio; los identificadores de compra y entrada son UUIDs.
- La aplicación establece UTC como zona horaria predeterminada de la JVM. La zona horaria JDBC de JPA y las marcas de tiempo generadas por el servicio/entidades también usan UTC.
- Los campos del contrato que representan instantes (`comienzaEn` y el `timestamp` de error) usan `Instant` en Java y se serializan en UTC con sufijo `Z`.

## Partially Implemented or Unverified
- El código define mapeos de errores, pero los tests actuales no verifican el cuerpo HTTP de error ni el mapeo de estado.
- El README documenta pruebas manuales con Postman/PostgreSQL. Los tests automatizados de este repositorio no verifican esas afirmaciones de manera independiente.
- La única clase de tests automatizados actual es `EntradaServiceImplTest`; sus 15 tests pasan y simulan los repositorios y la ejecución transaccional.
- El controller escribe una línea de diagnóstico en la salida estándar al emitir (`System.out.println`); ese mensaje no usa logging estructurado.

## Contract Alignment
- Para los campos revisados, `openapi-entradas.yaml` refleja la implementación: declara `fila` como `integer` nullable, `comienzaEn` como `date-time` con ejemplo UTC terminado en `Z`, y el error con `timestamp`, `status`, `error`, `mensaje` y `campos` opcional, sin una propiedad independiente `codigo`.

## Pending
- Agregar roles y políticas para `CLIENTE`, `ORGANIZADOR` y `ADMIN`, incluida la validación de titularidad de eventos si el producto la requiere.
- Definir e implementar autenticación/autorización para endpoints internos de comunicación entre servicios.
- Agregar tests de integración de controllers y base de datos para verificar comportamiento HTTP, migraciones Flyway, restricciones de esquema y emisión/cambios de estado concurrentes reales.
- Alinear el documento OpenAPI con los DTOs Java y el esquema de base de datos antes de considerarlo un contrato de consumo confiable.

## Verification Evidence
- El reporte Surefire disponible más reciente para `EntradaServiceImplTest` registra 15 tests, 0 fallos y 0 errores.
- Ese reporte aporta evidencia de nivel unitario; no demuestra que se hayan probado el servicio completo, el comportamiento de PostgreSQL ni las rutas desplegadas.
