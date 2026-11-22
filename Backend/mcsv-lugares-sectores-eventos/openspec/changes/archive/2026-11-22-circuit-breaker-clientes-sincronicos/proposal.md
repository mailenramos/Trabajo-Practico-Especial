# Proposal

## Why

Las llamadas síncronas a Reservas y Usuarios esperan sus timeouts ante fallas repetidas. Un circuito por servicio permite degradar más rápido sin cambiar los resultados ya ofrecidos por MS1.

## What Changes

- Proteger con Resilience4j la consulta de disponibilidad, los dos pasos remotos de publicación, la suspensión y reactivación, y la consulta de usuario para asignar personal.
- Mantener los resultados actuales ante errores y excluir todas las respuestas remotas `4xx` del cómputo de fallas.
- Configurar circuitos independientes por servicio, registrar sus transiciones en WARN y mostrar sus estados en Actuator sin que un circuito abierto haga fallar el health general.
- Mantener fuera del circuito el dispatcher, la cancelación recuperable y la finalización programada, que comparte el cliente de Reservas.
- Actualizar el README y agregar pruebas de apertura, rechazo sin llamada, recuperación, `409` y health.

## Capabilities

### New Capabilities

- `resiliencia-clientes-sincronicos`: circuitos independientes, clasificación de fallas, recuperación y observabilidad de las llamadas protegidas.

### Modified Capabilities

- `eventos`: omitir la consulta remota del detalle cuando el circuito de Reservas está abierto.
- `personal-acceso`: omitir la consulta a MS6 cuando su circuito está abierto.
- `despliegue-contenedores`: exponer estados de circuitos en health sin degradar el healthcheck de Docker.

## Impact

- Código de integración con Reservas y MS6, servicios que invocan Reservas, configuración y pruebas; no se modifican contratos HTTP públicos ni Java 21.
- Maven incorpora `io.github.resilience4j:resilience4j-spring-boot3` compatible con Spring Boot 3.4; AOP solo si se emplean anotaciones.
- Actuator pasará a mostrar componentes de health, incluidos los circuitos; se mantendrá sin detalles internos de componentes. El README describirá las propiedades y el comportamiento.
