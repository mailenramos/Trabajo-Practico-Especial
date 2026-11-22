# Design

## Context

MS1 usa Spring Boot 3.4.4 y Java 21. Los clientes `RestClient` traducen errores HTTP antes de que los servicios decidan la respuesta. `EventoLifecycleService` usa `cambiarEstadoVenta` tanto en solicitudes como en la finalización programada; publicar inicializa disponibilidad y luego activa la venta. Docker consulta `/actuator/health` y actualmente no se muestran componentes.

## Goals / Non-Goals

**Goals:** mantener el contrato de errores, aislar circuitos por destino y permitir observar su estado sin que el circuito abierto afecte el health general.

**Non-Goals:** cambiar Java, contratos remotos, timeouts, política de outbox o cancelación recuperable; proteger finalización programada; revertir efectos ya confirmados por Reservas.

## Decisions

1. Usar `resilience4j-spring-boot3`, con versión cuya compatibilidad con Boot 3.4 y Java 21 se verifique al implementar. Decorar programáticamente los puntos de llamada síncronos con el registro compartido; no añadir AOP si no se usan anotaciones. Alternativa descartada: anotar directamente `cambiarEstadoVenta`, pues también interceptaría la finalización.
2. `reservas` protege cada una de las dos llamadas de publicación por separado, detalle y venta administrativa; `usuarios` protege la consulta a MS6. El rechazo se traduce a las excepciones existentes para preservar los fallbacks y el JSON de error. Las validaciones locales siguen ejecutándose antes de cualquier invocación.
3. Preservar el status HTTP en una excepción tipada o clasificación equivalente antes de traducirlo al contrato actual. Excluir explícitamente todos los `4xx` de la ventana, no tratarlos como éxitos; así `409` en venta sigue siendo `409`, `409` en inicialización sigue siendo `503` y `404` de MS6 sigue siendo `404`. Registrar fallas de transporte, timeout, `5xx` y respuestas inválidas o inesperadas no `4xx`, sin contar errores de validación local. No deducir el código desde mensajes de excepción.
4. Configurar instancias `resilience4j.circuitbreaker.instances.reservas` y `.usuarios` con `sliding-window-type=COUNT_BASED`, `sliding-window-size=10`, `minimum-number-of-calls=10`, `failure-rate-threshold=50`, `wait-duration-in-open-state=30s` y `permitted-number-of-calls-in-half-open-state=1`; todas son propiedades sobrescribibles. Evitar que el umbral de llamadas lentas abra el circuito antes del criterio de fallas. Los valores son defaults operativos, no límites del contrato.
5. Escuchar cambios de estado de ambas instancias y registrarlos en WARN sin datos sensibles. El indicador estándar de Resilience4j con `allow-health-indicator-to-fail=false` mantiene `UP` el health general, pero con `show-details=never` oculta los estados. Mantenerlo y añadir `circuitbreakers` como contribuidor compuesto con solo el estado real de cada instancia, sin métricas; habilitar `show-components=always` y conservar el agregador estándar, sin normalización propia. La prueba HTTP comprueba `UP` con ambos circuitos abiertos y `DOWN` ante otra falla. Alternativa descartada: mostrar todos los detalles de health, pues expondría información adicional.

## Risks / Trade-offs

- [Ventana compartida] → Una operación afectada puede abrir el circuito para todas las llamadas síncronas al mismo servicio; se documenta y se prueba explícitamente.
- [Publicación parcial] → Si la inicialización remota fue confirmada antes de abrirse el circuito, el borrador local permanece; el reintento conserva la idempotencia existente y no presupone rollback remoto.
- [Mayor visibilidad en Actuator] → Los nombres y estados de componentes serán visibles; mantener ocultos sus detalles y no exponer Actuator en el Gateway.
- [Estados locales por instancia] → Cada proceso mantiene su propio circuito en memoria; un reinicio o réplica independiente parte de su propio estado.

## Migration Plan

Desplegar con los defaults y monitorear transiciones; ajustar las propiedades por servicio si es necesario. Para revertir, desplegar la versión anterior: no hay migración de datos ni cambio de contratos remotos. El estado en memoria se pierde al reiniciar.
