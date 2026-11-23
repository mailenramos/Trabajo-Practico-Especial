# Matriz de cumplimiento individual — Entrega 2

Esta matriz cubre MS2 y MS7. «Individual» indica implementación y pruebas en ambos servicios propios; no implica que todos los contratos con servicios del equipo hayan sido validados. La evidencia actual de concurrencia MS2 está en [evidencia-concurrencia-ms2.md](evidencia-concurrencia-ms2.md); el flujo grupal sin fila y su carga están en el [informe JMeter](../../../docs/pruebas-carga/informe.md). La [prueba grupal con fila activa](evidencia-compra-fila-activa.md) acredita el camino feliz y el rechazo sin turno válido con servicios reales.

## Historias funcionales

| Identificador | Estado individual | Evidencia | Integración o limitación pendiente |
| --- | --- | --- | --- |
| US-4.1 | Implementado y probado | `ReservaService`, `ReservaServiceTests`, JMeter sin fila y [flujo con fila activa](evidencia-compra-fila-activa.md) | La prueba grupal cubre un cliente y los pasos indicados en la evidencia. |
| US-4.2 | Implementado y probado | Cálculo bajo bloqueo en `ReservaService`; `ReservaConfirmacionServiceTests` y pruebas MySQL | Las reservas lógicamente vencidas no cuentan para el límite, aunque el inventario permanezca retenido hasta su liberación. |
| US-4.4 | Implementado y probado | `ReservaExpirationScheduler`, pruebas de confirmación y vencimiento | Coordinar pago rechazado y cancelación con MS3 y MS1 reales. |
| US-5.1 | Implementado y probado | Actualización transaccional; prueba con dos MS2 y MySQL compartido | El flujo de compra sin fila pasó por servicios reales en JMeter. |
| US-5.2 | Implementado y probado | Control condicional de capacidad y pruebas MySQL concurrentes | El flujo de compra sin fila pasó por servicios reales en JMeter. |
| US-5.3 | Parcial compartido | Confirmación idempotente de MS2 y pruebas | Los criterios de compra y pago pertenecen a MS3 y MS4. |
| US-5.4 | Implementado y probado | Bloqueos persistentes y prueba Compose con dos instancias MS2 | Validación del sistema grupal completo pendiente. |
| US-7.1 | Implementado y probado | `FilaVirtualService.ingresar`; pruebas MySQL y [flujo con JWT y fila activa](evidencia-compra-fila-activa.md) | La prueba grupal cubre el ingreso de un cliente. |
| US-7.2 | Implementado y probado | `FilaController.miTurno`, pruebas y [consulta por Gateway real](evidencia-compra-fila-activa.md) | La prueba grupal consultó el turno consumido del cliente autenticado. |
| US-7.3 | Implementado y probado | Procesamiento FIFO, `TurnoScheduler`, pruebas MySQL y Compose | Ninguna dependencia funcional adicional local. |
| US-7.4 | Implementado y probado | Consumo de MS7, coordinación de MS2 y [flujo con MS1 real](evidencia-compra-fila-activa.md) | Se acepta la ventana de desactivación posterior a la autorización. |
| US-7.5 | Implementado y probado | Pruebas de fallo remoto, respuesta perdida y reintento en Compose | Se acepta que un turno consumido pueda quedar sin reserva. |
| US-7.6 | Implementado y probado | Configuración y pruebas de cupo y duración | Validar titularidad con MS1 real. |
| US-7.7 | Implementado y probado | Coordinación local/remota, pruebas con MS1 simulado y [activación con MS1 real](evidencia-compra-fila-activa.md) | La prueba grupal no cubrió desactivación; no se garantiza el orden de operaciones administrativas opuestas concurrentes o demoradas. |
| US-7.8 | Implementado y probado | Consulta administrativa y pruebas | Validar roles y titularidad con Gateway y MS1 reales. |
| US-7.9 | Implementado y probado | Cancelación idempotente y pruebas; MS1 implementa el envío del aviso | El aviso de cancelación entre servicios reales no se verificó en la prueba de compra. |

## Requisitos transversales

| Identificador | Estado individual | Pendiente grupal o limitación |
| --- | --- | --- |
| US-12.1, US-12.2, T-11 y T-12 | Encabezados y control de roles probados | Validar JWT, rutas internas y titularidad a través del Gateway. La política para roles desconocidos permanece pendiente de acuerdo. |
| T-13 y T-14 | Imágenes y bases independientes probadas | Validar Compose general y bases del equipo. |
| T-15 | Circuit breakers, validación de respuestas, timeouts y reintentos GET probados en MS2 y MS7 | Confirmar el alcance tecnológico del requisito en el sistema grupal. Los circuitos son locales a cada instancia; no hay fallback permisivo. |
| T-16 | Se conserva el outbox de disponibilidad existente de MS2 | No acredita el flujo de notificaciones grupal; no hay outbox para la fila. |
| T-17 | Sin caché de disponibilidad, reservas ni autorizaciones | Caché de catálogo corresponde a MS1. |
| T-18 | Correlación, método, operación, estado, duración e identidad validada en logs HTTP; `CorrelacionFilterTests` verifica el contenido y el aislamiento del MDC en MS7 | Centralización con Alloy, Loki y Grafana y trazabilidad con servicios reales son grupales. |
| T-19 | Competencia con dos MS2 y MS1 simulado; compra sin fila en JMeter; [compra con fila activa y servicios reales](evidencia-compra-fila-activa.md) | La prueba con fila no acredita notificaciones ni fallos; la suite aislada completa necesita adaptar la propagación de token. |
| T-20 | 58 pruebas MySQL y escenarios de mismo asiento y último lugar con diez clientes, dos instancias MS2 y MySQL compartido | Las pruebas grupales de fila y el despliegue completo deben verificarse por el equipo. |
| T-21 | Ejecutado en el flujo de compra sin fila con cinco clientes concurrentes | [Resultados e informe JMeter](../../../docs/pruebas-carga/informe.md); no mide capacidad máxima. |
| T-22 | README, OpenAPI y documentación individual disponibles | Completar y verificar documentación, datos de prueba y despliegue grupales. |
| T-23 | Fuera del alcance individual de MS2 y MS7 | Integración de frontend y Gateway. |
