## 1. Base del proyecto
- [x] 1.1 Crear `Backend/mcsv-notificaciones` (Spring Boot, JPA, mail, Resilience4j) y arranque con contexto de test
- [x] 1.2 Configuración por variables de entorno (base de datos, SMTP, URL de MS6, máximos de reintento)

## 2. Modelo y persistencia
- [x] 2.1 Entidad `Notificacion`, enums de tipo y estado, y esquema con restricción única de deduplicación + test del repositorio
- [x] 2.2 Repositorio con actualización condicional PENDIENTE→PROCESANDO + test de concurrencia

## 3. Recepción
- [x] 3.1 DTO de aviso con validaciones y manejo de errores `{timestamp, status, error, mensaje, campos?}` + tests
- [x] 3.2 `POST /internal/notificaciones` idempotente (202 / reentrega) + tests
- [x] 3.3 Control de acceso de servicio (403) + test

## 4. Envío
- [x] 4.1 Cliente de MS6 para obtener el correo, con timeouts y circuit breaker, y test con doble
- [x] 4.2 Plantillas de contenido por tipo + test por tipo
- [x] 4.3 Servicio de envío SMTP detrás de una interfaz + test con doble
- [x] 4.4 Tarea programada de envío: estados, éxito y ERROR_FINAL por destinatario sin correo + tests

## 5. Reintentos y recuperación
- [x] 5.1 Reintentos con espera creciente, máximo y último error + tests
- [x] 5.2 Tarea de recuperación de PROCESANDO vencidas + test
- [x] 5.3 Guardado condicional del resultado del envío (estado PROCESANDO y misma toma) en todos los caminos + tests de no-pisado y concurrencia con la recuperación

## 6. Cierre
- [x] 6.1 OpenAPI del endpoint interno
- [x] 6.2 Dockerfile y variables de entorno documentadas
- [x] 6.3 Historias de usuario y diagrama del servicio en `docs/`
