# Design

## Context

El directorio MS8 solo contiene configuración inicial de OpenSpec. MS1 aporta el modelo vigente para Spring Boot 3.4.4, Java 21, Maven, Actuator, validación, springdoc, formato de errores, lectura de identidad y estructura de paquetes. MS8 tendrá persistencia propia en MongoDB standalone y no requiere coordinación transaccional con otros servicios. El contrato funcional está en `specs/preferencias-cliente/spec.md`.

## Goals / Non-Goals

**Goals:**

- Crear un servicio independiente que pueda ejecutarse desde el host o Docker en el puerto configurable `PORT` (default `8089`).
- Mantener una sola preferencia por usuario y permitir reemplazo completo, lectura y eliminación idempotente.
- Aislar MongoDB en la red privada de Compose y conectar la aplicación también a la red compartida `ticketya-net`.
- Probar autorización y persistencia sin requerir una base externa preexistente.

**Non-Goals:**

- Consultar eventos ni calcular o exponer recomendaciones.
- Añadir callbacks internos, integración con Notificaciones, outbox, caché, circuit breakers o interfaz.
- Añadir autenticación JWT local: el Gateway valida el token y MS8 consume los headers de identidad.

## Decisions

### Estructura y dependencias

- Usar `com.ticketya.preferencias` y paquetes `api`, `domain`, `repository`, `service`, `security` y `config`, con Maven wrapper.
- Alinear el POM con MS1: Spring Boot `3.4.4`, Java `21`, Actuator, Web MVC, Data MongoDB, Validation, springdoc OpenAPI `2.8.8`, Spring Boot Test y Testcontainers MongoDB/JUnit `1.20.4`.
- Mantener una aplicación Spring MVC pequeña: controlador para `/preferencias/me`, servicio para validación y operaciones, repositorio Mongo y tipos API para entrada/respuesta. Reutilizar el formato de error de MS1 (`timestamp`, `status`, `error`, `mensaje`, `campos`) y documentar el contrato mediante OpenAPI.

### Identidad y autorización

- Leer `X-User-Id` y `X-User-Roles` desde cada solicitud. Tratar un identificador ausente o en blanco como falta de identidad y responder `401`; si hay identidad pero ningún rol `CLIENTE`, responder `403`.
- Interpretar roles separados por comas, quitando espacios y normalizando a mayúsculas con `Locale.ROOT`, como en MS1. No confiar en un rol singular ni validar JWT dentro de MS8.

### Documento y reemplazo

- Representar el documento Mongo de la colección `preferencias` con `usuarioId` como campo `@Id` (valor persistido en `_id`), listas `tiposEvento`, `artistas`, `ciudades` y `actualizadoEn` como `Instant`.
- Guardar con `save` usando `usuarioId` como ID para que PUT sea un upsert de un único documento por cliente. El PUT reemplaza las tres listas completas; no necesita transacción. DELETE opera por ese mismo ID.
- Para GET sin documento, devolver las tres listas vacías y `actualizadoEn: null`, sin crear un documento.
- Asumir que las tres listas deben estar presentes en PUT; se admiten listas vacías para limpiar categorías y se rechazan listas nulas o ausentes. Recortar cada valor antes de comprobar que no quede vacío y que no esté repetido. La igualdad de preferencias es exacta y sensible a mayúsculas tras el recorte; no aplicar normalización semántica no solicitada.
- Asignar `actualizadoEn` al guardar con la hora actual UTC; Jackson lo representa como fecha/hora ISO-8601.

### Configuración local y Docker

- En `application.yml`, usar `server.port: ${PORT:8089}` y `spring.data.mongodb.uri: ${MONGODB_URI:mongodb://localhost:27018/ticketya_preferencias}`. Exponer `/actuator/health` y la documentación OpenAPI como en MS1.
- Crear Dockerfile multi-etapa basado en Maven con JDK 21 y runtime `eclipse-temurin:21-jre-alpine`; incluir herramienta HTTP para el healthcheck y ejecutar la aplicación con usuario no root. El contenedor activa `SPRING_PROFILES_ACTIVE=docker` por defecto.
- El Compose base define `preferencias` y `mongodb-preferencias`. Mongo usa `mongo:8.0`, base `ticketya_preferencias`, volumen persistente, sin `ports` y solo en una red del Compose marcada `internal`. La aplicación se une a esa red y a la red externa `ticketya-net`, también sin publicar puertos en el Compose base. Su URI interna apunta a `mongodb-preferencias:27017`.
- El overlay `docker-compose.dev.yml` publica `8089:8089` y `127.0.0.1:27018:27017`. Documentar creación previa de `ticketya-net`, ejecución desde host y Docker, variables configurables y uso de Actuator/Swagger en README.
- Incluir en Postman solo las operaciones CRUD, encabezados de identidad y variables de base URL/usuario, manteniendo el alcance mínimo.

### Pruebas

- Usar pruebas MVC para cada endpoint que verifiquen falta de identidad `401`, rol ajeno `403` y acceso de `CLIENTE` (GET/PUT `200`, DELETE `204`), además de parsing de roles, reemplazo y validación de listas.
- Usar Testcontainers con MongoDB `8.0` para guardar, recuperar, reemplazar y borrar el documento por `_id`; no depender de transacciones ni de un replica set.
- Mantener `mvnw.cmd test` como validación de la suite. Las pruebas Docker/Testcontainers requieren Docker disponible.

## Risks / Trade-offs

- [La red `ticketya-net` externa debe existir antes de levantar el Compose base] → Documentar `docker network create ticketya-net` en el README; la base permanece no publicada y aislada.
- [Un PUT de reemplazo puede borrar valores de una categoría omitida] → Exigir las tres listas en el cuerpo y permitir listas vacías explícitas para evitar ambigüedad.
- [La ausencia de un documento no tiene fecha de actualización real] → Representar `actualizadoEn` como `null` en la respuesta vacía, sin persistir preferencias implícitamente.

## Migration Plan

No hay migración de datos: MS8 parte sin documentos y MongoDB crea la base/colección al primer guardado. Para desplegar, iniciar el servicio y comprobar su healthcheck; para revertir, detener y retirar solo los contenedores del servicio, conservando el volumen salvo decisión operativa explícita de eliminar los datos.

## Open Questions

Ninguna para el alcance aprobado. Las decisiones sobre listas presentes en PUT y fecha nula en GET sin documento quedan fijadas aquí para que implementación, pruebas y documentación compartan el mismo contrato.
