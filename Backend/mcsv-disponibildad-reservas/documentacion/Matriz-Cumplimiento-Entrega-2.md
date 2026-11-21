# Matriz de cumplimiento individual — Entrega 2

Esta matriz cubre MS2 y MS7. «Individual» indica implementación y pruebas en ambos servicios propios; no implica que los contratos con servicios del equipo hayan sido validados. Las pruebas de integración usan MS1 simulado. Los resultados corresponden a la ejecución indicada en los README individuales.

## Historias funcionales

| Identificador | Estado individual | Evidencia | Integración o limitación pendiente |
| --- | --- | --- | --- |
| US-4.1 | Implementado y probado | `ReservaService`, `ReservaServiceTests`, Compose | Confirmar política con MS1 y acceso mediante Gateway reales. |
| US-4.2 | Implementado y probado | Cálculo bajo bloqueo en `ReservaService`; `ReservaConfirmacionServiceTests` y pruebas MySQL | Las reservas lógicamente vencidas no cuentan para el límite, aunque el inventario permanezca retenido hasta su liberación. |
| US-4.4 | Implementado y probado | `ReservaExpirationScheduler`, pruebas de confirmación y vencimiento | Coordinar pago rechazado y cancelación con MS3 y MS1 reales. |
| US-5.1 | Implementado y probado | Actualización transaccional de asientos; prueba Compose con dos MS2 | La integración grupal de compra queda pendiente. |
| US-5.2 | Implementado y probado | Control condicional de capacidad y pruebas MySQL concurrentes | La integración grupal de compra queda pendiente. |
| US-5.3 | Parcial compartido | Confirmación idempotente de MS2 y pruebas | Los criterios de compra y pago pertenecen a MS3 y MS4. |
| US-5.4 | Implementado y probado | Bloqueos persistentes y prueba Compose con dos instancias MS2 | Validación del sistema grupal completo pendiente. |
| US-7.1 | Implementado y probado | `FilaVirtualService.ingresar`; pruebas MySQL y Compose | Validar JWT y política con Gateway y MS1 reales. |
| US-7.2 | Implementado y probado | `FilaController.miTurno`, `FilaVirtualService.consultarTurno` y pruebas | Validar identidad propagada por Gateway real. |
| US-7.3 | Implementado y probado | Procesamiento FIFO, `TurnoScheduler`, pruebas MySQL y Compose | Ninguna dependencia funcional adicional local. |
| US-7.4 | Implementado y probado | Consumo de MS7, coordinación de MS2 y Compose | Validar política de MS1 real. Se acepta la ventana de desactivación posterior a la autorización. |
| US-7.5 | Implementado y probado | Pruebas de fallo remoto, respuesta perdida y reintento en Compose | Se acepta que un turno consumido pueda quedar sin reserva. |
| US-7.6 | Implementado y probado | Configuración y pruebas de cupo y duración | Validar titularidad con MS1 real. |
| US-7.7 | Implementado y probado | Coordinación local/remota y pruebas con MS1 simulado | No se garantiza el orden de operaciones administrativas opuestas concurrentes o demoradas; validar con MS1 real. |
| US-7.8 | Implementado y probado | Consulta administrativa y pruebas | Validar roles y titularidad con Gateway y MS1 reales. |
| US-7.9 | Implementado y probado | Cancelación idempotente y pruebas | MS1 real debe enviar la notificación. |

## Requisitos transversales

| Identificador | Estado individual | Pendiente grupal o limitación |
| --- | --- | --- |
| US-12.1, US-12.2, T-11 y T-12 | Encabezados y control de roles probados | Validar JWT, rutas internas y titularidad a través del Gateway. La política para roles desconocidos permanece pendiente de acuerdo. |
| T-13 y T-14 | Imágenes y bases independientes probadas | Validar Compose general y bases del equipo. |
| T-15 | Circuit breakers, validación de respuestas, timeouts y reintentos GET probados en MS2 y MS7 | Confirmar el alcance tecnológico del requisito en el sistema grupal. Los circuitos son locales a cada instancia; no hay fallback permisivo. |
| T-16 | Se conserva el outbox de disponibilidad existente de MS2 | No acredita el flujo de notificaciones grupal; no hay outbox para la fila. |
| T-17 | Sin caché de disponibilidad, reservas ni autorizaciones | Caché de catálogo corresponde a MS1. |
| T-18 | Correlación, método, operación, estado, duración e identidad validada en logs HTTP; `CorrelacionFilterTests` verifica el contenido y el aislamiento del MDC en MS7 | Centralización con Alloy, Loki y Grafana y trazabilidad con servicios reales son grupales. |
| T-19 | Compose aislado con dos instancias por servicio y MS1 simulado | Flujos con MS1, MS3, MS6, Gateway y notificaciones reales siguen pendientes. |
| T-20 | Pruebas MySQL y Compose de concurrencia | Validar el despliegue grupal. |
| T-21 | Pendiente grupal | Ejecutar JMeter sobre el flujo completo y presentar informe. |
| T-22 | README, OpenAPI y documentación individual disponibles | Completar y verificar documentación, datos de prueba y despliegue grupales. |
| T-23 | Fuera del alcance individual de MS2 y MS7 | Integración de frontend y Gateway. |
